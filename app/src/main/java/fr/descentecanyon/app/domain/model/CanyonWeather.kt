package fr.descentecanyon.app.domain.model

import java.time.Instant
import java.time.LocalDateTime

data class CanyonWeather(
    val target: WeatherTarget,
    val timezone: String,
    val fetchedAt: Instant,
    val hourly: List<HourlyPrecipitation> = emptyList(),
    val past24HoursPrecipitationMm: Double,
    val past48HoursPrecipitationMm: Double,
    val past72HoursPrecipitationMm: Double,
    val next24HoursPrecipitationMm: Double,
    val next48HoursPrecipitationMm: Double,
    val maxHourlyPrecipitationPast72HoursMm: Double,
    val maxPrecipitationProbabilityNext24Hours: Int? = null,
    val dailyForecasts: List<DailyWeatherForecast> = emptyList(),
)

data class WeatherTarget(
    val latitude: Double,
    val longitude: Double,
    val source: WeatherLocationSource,
)

enum class WeatherLocationSource {
    WATERSHED_CENTER,
    ENTRY,
    UPSTREAM_PARKING,
    EXIT,
    DOWNSTREAM_PARKING,
    REMARKABLE_POINT,
    ESCAPE,
    UNKNOWN,
}

data class HourlyPrecipitation(
    val dateTime: LocalDateTime,
    val precipitationMm: Double,
    val rainMm: Double? = null,
    val showersMm: Double? = null,
    val precipitationProbabilityPercent: Int? = null,
    val weatherCode: Int? = null,
)

data class DailyWeatherForecast(
    val date: java.time.LocalDate,
    val precipitationMm: Double,
    val temperatureMin: Double?,
    val temperatureMax: Double?,
    val weatherCode: Int?,
)
