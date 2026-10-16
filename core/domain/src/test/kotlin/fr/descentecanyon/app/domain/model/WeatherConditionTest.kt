package fr.descentecanyon.app.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherConditionTest {

    @Test
    fun `wmo codes are grouped`() {
        assertEquals(WeatherCondition.CLEAR, WeatherCondition.fromWmoCode(0))
        assertEquals(WeatherCondition.PARTLY_CLOUDY, WeatherCondition.fromWmoCode(2))
        assertEquals(WeatherCondition.FOG, WeatherCondition.fromWmoCode(48))
        assertEquals(WeatherCondition.DRIZZLE, WeatherCondition.fromWmoCode(53))
        assertEquals(WeatherCondition.RAIN, WeatherCondition.fromWmoCode(81))
        assertEquals(WeatherCondition.SNOW, WeatherCondition.fromWmoCode(86))
        assertEquals(WeatherCondition.THUNDERSTORM, WeatherCondition.fromWmoCode(96))
        assertEquals(WeatherCondition.UNKNOWN, WeatherCondition.fromWmoCode(null))
        assertEquals(WeatherCondition.UNKNOWN, WeatherCondition.fromWmoCode(42))
    }

    @Test
    fun `the first stormy day and the storm hours of the next 24 hours are found`() {
        val now = LocalDateTime.of(2026, 7, 14, 10, 0)
        val weather = CanyonWeather(
            target = WeatherTarget(45.0, 6.0, WeatherLocationSource.ENTRY),
            timezone = "Europe/Paris",
            fetchedAt = Instant.EPOCH,
            hourly = listOf(
                HourlyPrecipitation(now.minusHours(1), 0.0, weatherCode = 95),
                HourlyPrecipitation(now.plusHours(5), 4.0, weatherCode = 95),
                HourlyPrecipitation(now.plusHours(30), 4.0, weatherCode = 99),
            ),
            daily = listOf(
                DailyWeather(LocalDate.of(2026, 7, 14), weatherCode = 3),
                DailyWeather(LocalDate.of(2026, 7, 15), weatherCode = 95),
                DailyWeather(LocalDate.of(2026, 7, 16), weatherCode = 61),
                DailyWeather(LocalDate.of(2026, 7, 17), weatherCode = 96),
            ),
        )

        // The storm of 09:00 is over; the next one is at 15:00.
        assertEquals(ThunderstormForecast(LocalDate.of(2026, 7, 14), LocalTime.of(15, 0)), weather.nextThunderstorm(now))
        // Beyond the hourly range (here today only), the daily forecast gives the day.
        assertEquals(
            ThunderstormForecast(LocalDate.of(2026, 7, 15), null),
            weather.copy(hourly = weather.hourly.take(1)).nextThunderstorm(now),
        )
        // The hourly forecast covers tomorrow without storm: the daily storm of tomorrow is ignored.
        assertEquals(
            ThunderstormForecast(LocalDate.of(2026, 7, 17), null),
            weather.copy(hourly = listOf(HourlyPrecipitation(now.plusHours(30), 1.0, weatherCode = 3))).nextThunderstorm(now),
        )
        assertNull(weather.copy(hourly = emptyList(), daily = weather.daily.take(1)).nextThunderstorm(now))
        assertNull(weather.dayOf(LocalDate.of(2026, 7, 20)))
    }
}
