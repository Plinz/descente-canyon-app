package fr.descentecanyon.app.domain.model

import java.time.Instant
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RainOutlookTest {

    private val downloadedAt = LocalDateTime.of(2026, 7, 14, 10, 0)

    /** 1 mm every hour from 72 hours before the download to 48 hours after, 10 % chance each. */
    private val weather = CanyonWeather(
        target = WeatherTarget(45.0, 6.0, WeatherLocationSource.ENTRY),
        timezone = "Europe/Paris",
        fetchedAt = Instant.EPOCH,
        hourly = (-71..48).map { offset ->
            HourlyPrecipitation(downloadedAt.plusHours(offset.toLong()), 1.0, precipitationProbabilityPercent = 10)
        }.shuffled(),
    )

    @Test
    fun `the hour ending now is past and the windows are 72 and 48 hours`() {
        val rain = weather.rainAround(downloadedAt.plusMinutes(40))

        assertEquals(72, rain.past.size)
        assertEquals(downloadedAt, rain.past.last().dateTime)
        assertEquals(48, rain.next.size)
        assertEquals(downloadedAt.plusHours(1), rain.next.first().dateTime)
        assertEquals(72.0, rain.pastTotalMm, 0.001)
        assertEquals(48.0, rain.nextTotalMm, 0.001)
        assertEquals(120, rain.hours.size)
    }

    @Test
    fun `a forecast read later from the cache covers fewer coming hours`() {
        val rain = weather.rainAround(downloadedAt.plusHours(30))

        assertEquals(72, rain.past.size)
        assertEquals(18, rain.next.size)
        assertEquals(18.0, rain.nextTotalMm, 0.001)
    }

    @Test
    fun `the chance of rain is the highest of the coming hours and unknown without forecast`() {
        val stormy = weather.copy(
            hourly = weather.hourly.map {
                if (it.dateTime == downloadedAt.plusHours(20)) it.copy(precipitationProbabilityPercent = 85) else it
            },
        )

        assertEquals(85, stormy.rainAround(downloadedAt).maxProbabilityPercent)
        assertNull(weather.rainAround(downloadedAt.plusHours(60)).maxProbabilityPercent)
    }
}
