package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.data.network.retryOnConnectivityFailure
import fr.descentecanyon.app.data.remote.weather.DailyForecastDto
import fr.descentecanyon.app.data.remote.weather.HourlyForecastDto
import fr.descentecanyon.app.data.remote.weather.OpenMeteoForecastDto
import fr.descentecanyon.app.data.remote.weather.OpenMeteoRemoteSource
import fr.descentecanyon.app.domain.coroutines.IoDispatcher
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.DailyWeather
import fr.descentecanyon.app.domain.model.HourlyPrecipitation
import fr.descentecanyon.app.domain.model.WeatherLocationSource
import fr.descentecanyon.app.domain.model.WeatherTarget
import fr.descentecanyon.app.domain.repository.WeatherRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Singleton
class WeatherRepositoryImpl @Inject constructor(
    private val remoteSource: OpenMeteoRemoteSource,
    private val keyValueDao: KeyValueDao,
    private val clock: Clock,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : WeatherRepository {

    override suspend fun getCanyonWeather(detail: CanyonDetail): Result<CanyonWeather> = withContext(ioDispatcher) {
        suspendRunCatching {
            val target = WeatherTargetResolver.resolve(detail)
                ?: appError(AppError.DataUnavailable)
            val response = retryOnConnectivityFailure {
                remoteSource.fetchForecast(target.latitude, target.longitude)
            }
            val fetchedAt = clock.instant()
            val weather = response.toCanyonWeather(target, fetchedAt, isFromCache = false)
                ?: appError(AppError.DataUnavailable)
            saveForOffline(
                detail.canyon.id,
                CachedForecast(fetchedAt.toEpochMilli(), target.latitude, target.longitude, target.source.name, response),
            )
            weather
        }
    }

    override suspend fun getCachedCanyonWeather(canyonId: Int): CanyonWeather? = withContext(ioDispatcher) {
        val cached = keyValueDao.get(cacheKey(canyonId))
            ?.let { payload -> runCatching { json.decodeFromString(CachedForecast.serializer(), payload) }.getOrNull() }
            ?: return@withContext null
        val fetchedAt = Instant.ofEpochMilli(cached.fetchedAtEpochMs)
        if (Duration.between(fetchedAt, clock.instant()) > MAX_CACHE_AGE) return@withContext null
        val source = WeatherLocationSource.entries.firstOrNull { it.name == cached.targetSource } ?: WeatherLocationSource.UNKNOWN
        cached.forecast.toCanyonWeather(WeatherTarget(cached.targetLatitude, cached.targetLongitude, source), fetchedAt, isFromCache = true)
    }

    private suspend fun saveForOffline(canyonId: Int, forecast: CachedForecast) {
        // A failed write only costs the offline copy.
        runCatching {
            keyValueDao.put(
                KeyValueEntity(cacheKey(canyonId), json.encodeToString(CachedForecast.serializer(), forecast), updatedAt = clock.millis()),
            )
            keyValueDao.deleteByPrefixOlderThan(CACHE_KEY_PREFIX, clock.millis() - MAX_CACHE_AGE.toMillis())
        }
    }

    /** Aggregates around the current hour, so a forecast read from the cache is still meaningful. */
    private fun OpenMeteoForecastDto.toCanyonWeather(target: WeatherTarget, fetchedAt: Instant, isFromCache: Boolean): CanyonWeather? {
        val zoneId = timezone.toZoneIdOrUtc()
        val now = ZonedDateTime.now(clock.withZone(zoneId)).withMinute(0).withSecond(0).withNano(0).toLocalDateTime()
        val hourly = hourly.toHourlyPrecipitation()
        if (hourly.isEmpty()) return null

        val pastHours = hourly.filter { !it.dateTime.isAfter(now) }
        val futureHours = hourly.filter { it.dateTime.isAfter(now) }
        return CanyonWeather(
            target = target,
            timezone = timezone,
            fetchedAt = fetchedAt,
            hourly = hourly,
            past24HoursPrecipitationMm = pastHours.sumLast(24),
            past48HoursPrecipitationMm = pastHours.sumLast(48),
            past72HoursPrecipitationMm = pastHours.sumLast(72),
            next24HoursPrecipitationMm = futureHours.sumFirst(24),
            next48HoursPrecipitationMm = futureHours.sumFirst(48),
            maxHourlyPrecipitationPast72HoursMm = pastHours.maxLast(72),
            maxPrecipitationProbabilityNext24Hours = futureHours
                .take(24)
                .mapNotNull { it.precipitationProbabilityPercent }
                .maxOrNull(),
            daily = daily.toDailyWeather().filter { !it.date.isBefore(now.toLocalDate()) },
            isFromCache = isFromCache,
        )
    }

    private fun String.toZoneIdOrUtc(): ZoneId = runCatching { ZoneId.of(this) }.getOrDefault(ZoneId.of("UTC"))

    private fun HourlyForecastDto?.toHourlyPrecipitation(): List<HourlyPrecipitation> {
        if (this == null) return emptyList()

        return time.mapIndexedNotNull { index, rawTime ->
            val dateTime = runCatching { LocalDateTime.parse(rawTime) }.getOrNull() ?: return@mapIndexedNotNull null
            HourlyPrecipitation(
                dateTime = dateTime,
                precipitationMm = precipitation.getOrNull(index) ?: 0.0,
                rainMm = rain.getOrNull(index),
                showersMm = showers.getOrNull(index),
                precipitationProbabilityPercent = precipitationProbability.getOrNull(index),
                weatherCode = weatherCode.getOrNull(index),
            )
        }
    }

    private fun DailyForecastDto?.toDailyWeather(): List<DailyWeather> {
        if (this == null) return emptyList()

        return time.mapIndexedNotNull { index, rawDate ->
            val date = runCatching { LocalDate.parse(rawDate) }.getOrNull() ?: return@mapIndexedNotNull null
            DailyWeather(
                date = date,
                weatherCode = weatherCode.getOrNull(index),
                precipitationSumMm = precipitationSum.getOrNull(index),
                precipitationProbabilityMaxPercent = precipitationProbabilityMax.getOrNull(index),
                temperatureMaxC = temperature2mMax.getOrNull(index),
                temperatureMinC = temperature2mMin.getOrNull(index),
                sunrise = sunrise.getOrNull(index).toLocalTimeOrNull(),
                sunset = sunset.getOrNull(index).toLocalTimeOrNull(),
            )
        }
    }

    private fun String?.toLocalTimeOrNull(): LocalTime? = this?.let { runCatching { LocalDateTime.parse(it).toLocalTime() }.getOrNull() }

    private fun List<HourlyPrecipitation>.sumLast(hours: Int): Double = takeLast(hours).sumOf { it.precipitationMm }

    private fun List<HourlyPrecipitation>.sumFirst(hours: Int): Double = take(hours).sumOf { it.precipitationMm }

    private fun List<HourlyPrecipitation>.maxLast(hours: Int): Double = takeLast(hours).maxOfOrNull { it.precipitationMm } ?: 0.0

    /** Forecast kept for offline use, with the point it was computed for. */
    @Serializable
    internal data class CachedForecast(
        val fetchedAtEpochMs: Long,
        val targetLatitude: Double,
        val targetLongitude: Double,
        val targetSource: String,
        val forecast: OpenMeteoForecastDto,
    )

    private companion object {
        const val CACHE_KEY_PREFIX = "weather.forecast."

        /** The hourly forecast covers 48 hours and the daily one 6 days: older copies are of no use. */
        val MAX_CACHE_AGE: Duration = Duration.ofDays(3)

        val json = Json { ignoreUnknownKeys = true }

        fun cacheKey(canyonId: Int): String = "$CACHE_KEY_PREFIX$canyonId"
    }
}
