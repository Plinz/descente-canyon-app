package fr.descentecanyon.app.domain.model

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SunCalculatorTest {

    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun `vercors in mid october matches the forecast service`() {
        // Open-Meteo gives 07:53 and 18:53 for 45.18 N, 5.49 E on 2026-10-15.
        val times = SunCalculator.sunTimes(LocalDate.of(2026, 10, 15), 45.18, 5.49, paris)

        assertClose(LocalTime.of(7, 53), times.sunrise!!)
        assertClose(LocalTime.of(18, 53), times.sunset!!)
    }

    @Test
    fun `summer solstice in the pyrenees and the reunion island`() {
        // Reference: NOAA solar equations (sunrise and sunset at a 90.833 degree zenith).
        val pyrenees = SunCalculator.sunTimes(LocalDate.of(2026, 6, 21), 42.8, 0.3, paris)
        assertClose(LocalTime.of(6, 19), pyrenees.sunrise!!)
        assertClose(LocalTime.of(21, 40), pyrenees.sunset!!)

        // Austral winter at La Réunion (UTC+4).
        val reunion = SunCalculator.sunTimes(LocalDate.of(2026, 6, 21), -21.1, 55.5, ZoneId.of("Indian/Reunion"))
        assertClose(LocalTime.of(6, 53), reunion.sunrise!!)
        assertClose(LocalTime.of(17, 44), reunion.sunset!!)
    }

    @Test
    fun `no sunset during the polar day`() {
        val times = SunCalculator.sunTimes(LocalDate.of(2026, 6, 21), 78.2, 15.6, ZoneId.of("Arctic/Longyearbyen"))

        assertNull(times.sunset)
    }

    private fun assertClose(expected: LocalTime, actual: LocalTime) {
        val gap = Duration.between(expected, actual).abs()
        assertTrue("$actual is not close to $expected", gap <= Duration.ofMinutes(3))
    }
}
