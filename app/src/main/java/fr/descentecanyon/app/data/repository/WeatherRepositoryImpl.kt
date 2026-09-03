package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.dao.DailyWeatherDao
import fr.descentecanyon.app.data.local.entity.DailyWeatherEntity
import fr.descentecanyon.app.data.remote.weather.OpenMeteoRemoteSource
import fr.descentecanyon.app.di.IoDispatcher
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.DailyWeatherForecast
import fr.descentecanyon.app.domain.model.HourlyPrecipitation
import fr.descentecanyon.app.domain.repository.WeatherRepository
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Singleton
class WeatherRepositoryImpl @Inject constructor(
    private val remoteSource: OpenMeteoRemoteSource,
    private val dailyWeatherDao: DailyWeatherDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : WeatherRepository {

    override suspend fun getCanyonWeather(detail: CanyonDetail): Result<CanyonWeather> {
        return withContext(ioDispatcher) {
            runCatching {
                val target = WeatherTargetResolver.resolve(detail)
                    ?: throw IllegalStateException("Aucune coordonnée exploitable pour la météo")

                try {
                    val response = retryOpenMeteoRequest {
                        remoteSource.fetchForecast(target.latitude, target.longitude)
                    }
                    val zoneId = response.timezone.toZoneIdOrUtc()
                    val now = ZonedDateTime.now(zoneId)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0)

                    val hourly = response.hourly.toHourlyPrecipitation()
                    val daily = response.daily.toDailyWeatherForecast()

                    // Cache the daily forecast
                    val fetchedAtEpochMs = Instant.now().toEpochMilli()
                    val entities = daily.map { forecast ->
                        DailyWeatherEntity(
                            canyonId = detail.canyon.id,
                            date = forecast.date.toString(),
                            timezone = response.timezone,
                            targetLatitude = target.latitude,
                            targetLongitude = target.longitude,
                            targetSource = target.source.name,
                            sourceKind = "OPEN_METEO",
                            precipitationSum = forecast.precipitationMm,
                            temperature2mMin = forecast.temperatureMin,
                            temperature2mMax = forecast.temperatureMax,
                            weatherCode = forecast.weatherCode,
                            fetchedAtEpochMs = fetchedAtEpochMs,
                        )
                    }
                    if (entities.isNotEmpty()) {
                        dailyWeatherDao.insertAll(entities)
                    }

                    val pastHours = hourly.filter { !it.dateTime.atZone(zoneId).isAfter(now) }
                    val futureHours = hourly.filter { it.dateTime.atZone(zoneId).isAfter(now) }

                    CanyonWeather(
                        target = target,
                        timezone = response.timezone,
                        fetchedAt = Instant.now(),
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
                        dailyForecasts = daily,
                    )
                } catch (e: Throwable) {
                    if (!e.isRetryableOpenMeteoFailure()) throw e
                    // Try to fallback to offline daily weather
                    val todayStr = LocalDate.now().toString()
                    val next5DaysStr = LocalDate.now().plusDays(5).toString()
                    val cachedEntities = dailyWeatherDao.getByCanyonIdAndDateRange(detail.canyon.id, todayStr, next5DaysStr)

                    if (cachedEntities.isEmpty()) {
                        throw e // No offline data
                    }

                    val offlineDaily = cachedEntities.map { entity ->
                        DailyWeatherForecast(
                            date = LocalDate.parse(entity.date),
                            precipitationMm = entity.precipitationSum ?: 0.0,
                            temperatureMin = entity.temperature2mMin,
                            temperatureMax = entity.temperature2mMax,
                            weatherCode = entity.weatherCode,
                        )
                    }

                    CanyonWeather(
                        target = target,
                        timezone = cachedEntities.first().timezone,
                        fetchedAt = Instant.ofEpochMilli(cachedEntities.first().fetchedAtEpochMs),
                        hourly = emptyList(),
                        past24HoursPrecipitationMm = 0.0,
                        past48HoursPrecipitationMm = 0.0,
                        past72HoursPrecipitationMm = 0.0,
                        next24HoursPrecipitationMm = 0.0,
                        next48HoursPrecipitationMm = 0.0,
                        maxHourlyPrecipitationPast72HoursMm = 0.0,
                        maxPrecipitationProbabilityNext24Hours = null,
                        dailyForecasts = offlineDaily,
                    )
                }
            }
        }
    }

    private fun String.toZoneIdOrUtc(): ZoneId {
        return runCatching { ZoneId.of(this) }.getOrDefault(ZoneId.of("UTC"))
    }

    private fun fr.descentecanyon.app.data.remote.weather.HourlyForecastDto?.toHourlyPrecipitation(): List<HourlyPrecipitation> {
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

    private fun fr.descentecanyon.app.data.remote.weather.DailyForecastDto?.toDailyWeatherForecast(): List<DailyWeatherForecast> {
        if (this == null) return emptyList()

        return time.mapIndexedNotNull { index, rawTime ->
            val date = runCatching { LocalDate.parse(rawTime) }.getOrNull() ?: return@mapIndexedNotNull null
            DailyWeatherForecast(
                date = date,
                precipitationMm = precipitationSum.getOrNull(index) ?: 0.0,
                temperatureMin = temperature2mMin.getOrNull(index),
                temperatureMax = temperature2mMax.getOrNull(index),
                weatherCode = weatherCode.getOrNull(index),
            )
        }
    }

    private fun List<HourlyPrecipitation>.sumLast(hours: Int): Double {
        return takeLast(hours).sumOf { it.precipitationMm }
    }

    private fun List<HourlyPrecipitation>.sumFirst(hours: Int): Double {
        return take(hours).sumOf { it.precipitationMm }
    }

    private fun List<HourlyPrecipitation>.maxLast(hours: Int): Double {
        return takeLast(hours).maxOfOrNull { it.precipitationMm } ?: 0.0
    }

    private suspend fun <T> retryOpenMeteoRequest(block: suspend () -> T): T {
        var lastFailure: Throwable? = null
        repeat(OPEN_METEO_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (throwable: Throwable) {
                lastFailure = throwable
                if (attempt == OPEN_METEO_ATTEMPTS - 1 || !throwable.isRetryableOpenMeteoFailure()) {
                    throw throwable
                }
                delay(OPEN_METEO_RETRY_DELAY_MS)
            }
        }
        throw lastFailure ?: IllegalStateException("Open-Meteo request failed")
    }

    private fun Throwable.isRetryableOpenMeteoFailure(): Boolean {
        return generateSequence(this) { it.cause }.any { cause ->
            cause is UnknownHostException ||
                cause is UnresolvedAddressException ||
                cause is ConnectException ||
                cause is SocketTimeoutException ||
                cause.message?.contains("timeout", ignoreCase = true) == true ||
                cause.message?.contains("timed out", ignoreCase = true) == true
        }
    }

    private companion object {
        const val OPEN_METEO_ATTEMPTS = 2
        const val OPEN_METEO_RETRY_DELAY_MS = 600L
    }
}
