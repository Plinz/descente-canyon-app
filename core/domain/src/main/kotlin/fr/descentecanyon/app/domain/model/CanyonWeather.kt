package fr.descentecanyon.app.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class CanyonWeather(
    val target: WeatherTarget,
    val timezone: String,
    val fetchedAt: Instant,
    /** From [PAST_HOURS] hours before the download to [FORECAST_HOURS] hours after. */
    val hourly: List<HourlyPrecipitation> = emptyList(),
    /** Today and the next days. */
    val daily: List<DailyWeather> = emptyList(),
    /** True when the network was unavailable and the last forecast downloaded is shown. */
    val isFromCache: Boolean = false,
) {
    /** Time zone of the weather point, which the hourly and daily times are expressed in. */
    val zoneId: ZoneId get() = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneOffset.UTC)

    /**
     * Rain of the last [PAST_HOURS] hours and of the next [FORECAST_HOURS] ones around [now]. An
     * hourly value is the rain of the hour before its time, so the hour ending at [now] is past.
     * A forecast read from the cache covers fewer coming hours.
     */
    fun rainAround(now: LocalDateTime): RainOutlook {
        val currentHour = now.truncatedTo(ChronoUnit.HOURS)
        val sorted = hourly.sortedBy { it.dateTime }
        return RainOutlook(
            past = sorted.filter { !it.dateTime.isAfter(currentHour) }.takeLast(PAST_HOURS),
            next = sorted.filter { it.dateTime.isAfter(currentHour) }.take(FORECAST_HOURS),
        )
    }

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

    companion object {
        const val PAST_HOURS = 72
        const val FORECAST_HOURS = 48
    }
}

/** Hourly rain before and after now, oldest first. */
data class RainOutlook(val past: List<HourlyPrecipitation>, val next: List<HourlyPrecipitation>) {
    val pastTotalMm: Double get() = past.sumOf { it.precipitationMm }
    val nextTotalMm: Double get() = next.sumOf { it.precipitationMm }

    /** Highest chance of rain over the coming hours, when the forecast gives one. */
    val maxProbabilityPercent: Int? get() = next.mapNotNull { it.precipitationProbabilityPercent }.maxOrNull()

    val hours: List<HourlyPrecipitation> get() = past + next
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
