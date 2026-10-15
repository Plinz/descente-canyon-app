package fr.descentecanyon.app.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

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
    /** Today and the next days. */
    val daily: List<DailyWeather> = emptyList(),
    /** True when the network was unavailable and the last forecast downloaded is shown. */
    val isFromCache: Boolean = false,
) {
    /** Forecast of [date], if it is still covered. */
    fun dayOf(date: LocalDate): DailyWeather? = daily.firstOrNull { it.date == date }

    /**
     * Next thunderstorm after [now]: the hourly forecast (48 hours) gives its time, the daily one
     * the later days. A storm of today that is already over is not announced.
     */
    fun nextThunderstorm(now: LocalDateTime): ThunderstormForecast? {
        val stormHour = hourly.firstOrNull {
            it.dateTime.isAfter(now) && WeatherCondition.fromWmoCode(it.weatherCode) == WeatherCondition.THUNDERSTORM
        }
        if (stormHour != null) return ThunderstormForecast(stormHour.dateTime.toLocalDate(), stormHour.dateTime.toLocalTime())
        val hourlyEnd = hourly.maxOfOrNull { it.dateTime }?.toLocalDate() ?: now.toLocalDate()
        val stormDay = daily.firstOrNull { it.date.isAfter(hourlyEnd) && it.condition == WeatherCondition.THUNDERSTORM }
        return stormDay?.let { ThunderstormForecast(it.date, null) }
    }
}

/** A forecast thunderstorm: its day, and its time when the hourly forecast covers it. */
data class ThunderstormForecast(val date: LocalDate, val time: LocalTime?)

/** Daily forecast at the weather point of a canyon. */
data class DailyWeather(
    val date: LocalDate,
    val weatherCode: Int? = null,
    val precipitationSumMm: Double? = null,
    val precipitationProbabilityMaxPercent: Int? = null,
    val temperatureMaxC: Double? = null,
    val temperatureMinC: Double? = null,
    val sunrise: LocalTime? = null,
    val sunset: LocalTime? = null,
) {
    val condition: WeatherCondition get() = WeatherCondition.fromWmoCode(weatherCode)
}

/** Groups of WMO weather interpretation codes (as returned by Open-Meteo). */
enum class WeatherCondition {
    CLEAR,
    PARTLY_CLOUDY,
    CLOUDY,
    FOG,
    DRIZZLE,
    RAIN,
    SNOW,
    THUNDERSTORM,
    UNKNOWN,
    ;

    companion object {
        fun fromWmoCode(code: Int?): WeatherCondition = when (code) {
            null -> UNKNOWN
            0 -> CLEAR
            1, 2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            in 51..57 -> DRIZZLE
            in 61..67, in 80..82 -> RAIN
            in 71..77, 85, 86 -> SNOW
            in 95..99 -> THUNDERSTORM
            else -> UNKNOWN
        }
    }
}

data class WeatherTarget(val latitude: Double, val longitude: Double, val source: WeatherLocationSource)

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
