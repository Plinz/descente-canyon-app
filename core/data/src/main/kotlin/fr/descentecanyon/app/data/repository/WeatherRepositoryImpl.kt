package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.network.retryOnConnectivityFailure
import fr.descentecanyon.app.data.remote.weather.OpenMeteoRemoteSource
import fr.descentecanyon.app.domain.coroutines.IoDispatcher
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.HourlyPrecipitation
import fr.descentecanyon.app.domain.repository.WeatherRepository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
class WeatherRepositoryImpl @Inject constructor(
    private val remoteSource: OpenMeteoRemoteSource,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : WeatherRepository {

    override suspend fun getCanyonWeather(detail: CanyonDetail): Result<CanyonWeather> = withContext(ioDispatcher) {
        suspendRunCatching {
            val target = WeatherTargetResolver.resolve(detail)
                ?: appError(AppError.DataUnavailable)
            val response = retryOnConnectivityFailure {
                remoteSource.fetchForecast(target.latitude, target.longitude)
            }
            val zoneId = response.timezone.toZoneIdOrUtc()
            val now = ZonedDateTime.now(zoneId)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)

            val hourly = response.hourly.toHourlyPrecipitation()
            if (hourly.isEmpty()) {
                appError(AppError.DataUnavailable)
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
            )
        }
    }

    private fun String.toZoneIdOrUtc(): ZoneId = runCatching { ZoneId.of(this) }.getOrDefault(ZoneId.of("UTC"))

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

    private fun List<HourlyPrecipitation>.sumLast(hours: Int): Double = takeLast(hours).sumOf { it.precipitationMm }

    private fun List<HourlyPrecipitation>.sumFirst(hours: Int): Double = take(hours).sumOf { it.precipitationMm }

    private fun List<HourlyPrecipitation>.maxLast(hours: Int): Double = takeLast(hours).maxOfOrNull { it.precipitationMm } ?: 0.0
}
