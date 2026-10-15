package fr.descentecanyon.app.domain.model

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutingPlanTest {

    private val start = LocalDateTime.of(2026, 7, 14, 9, 0)

    @Test
    fun `the timeline adds the sheet durations as ranges`() {
        val timeline = OutingTimeline.compute(
            start = start,
            approach = CanyonDuration.parse("20 à 45min"),
            descent = CanyonDuration.parse("3h"),
            back = CanyonDuration.parse("1h-1h30"),
        )

        assertEquals(TimeWindow(start.plusMinutes(20), start.plusMinutes(45)), timeline.descentStart)
        assertEquals(TimeWindow(start.plusMinutes(200), start.plusMinutes(225)), timeline.descentEnd)
        assertEquals(TimeWindow(start.plusMinutes(260), start.plusMinutes(315)), timeline.backAtCar)
        assertEquals(Duration.ofMinutes(315), timeline.longestDuration)
    }

    @Test
    fun `no approach counts as zero and an unknown step stops the timeline`() {
        val noApproach = OutingTimeline.compute(
            start,
            CanyonDuration.parse("néant"),
            CanyonDuration.parse("2h"),
            CanyonDuration.parse("??"),
        )

        assertEquals(TimeWindow(start, start), noApproach.descentStart)
        assertEquals(TimeWindow(start.plusHours(2), start.plusHours(2)), noApproach.descentEnd)
        assertNull(noApproach.backAtCar)
        assertNull(noApproach.longestDuration)

        val unknownDescent = OutingTimeline.compute(
            start,
            CanyonDuration.parse("10min"),
            CanyonDuration.parse("??"),
            CanyonDuration.parse("10min"),
        )
        assertNull(unknownDescent.descentEnd)
        assertNull(unknownDescent.backAtCar)
    }

    @Test
    fun `daylight is fine with half an hour left, late otherwise with the latest start`() {
        val timeline = OutingTimeline.compute(start, CanyonDuration.parse("1h"), CanyonDuration.parse("5h"), CanyonDuration.parse("1h"))

        assertEquals(DaylightCheck.Ok(LocalTime.of(21, 0), Duration.ofHours(5)), DaylightCheck.of(timeline, LocalTime.of(21, 0)))

        val afternoon = OutingTimeline.compute(
            start.withHour(14),
            CanyonDuration.parse("1h"),
            CanyonDuration.parse("5h"),
            CanyonDuration.parse("1h"),
        )
        val late = DaylightCheck.of(afternoon, LocalTime.of(19, 0))
        assertEquals(DaylightCheck.Late(LocalTime.of(19, 0), LocalTime.of(11, 30)), late)

        assertEquals(DaylightCheck.Unknown, DaylightCheck.of(timeline, null))
    }

    @Test
    fun `emergency numbers follow the canyon country`() {
        assertEquals(listOf("112", "1414", "144"), EmergencyNumbers.forCountries("Suisse").map { it.number })
        assertEquals(listOf("112", "15", "18"), EmergencyNumbers.forCountries("France , Espagne").map { it.number })
        assertEquals(listOf("111"), EmergencyNumbers.forCountries("Nouvelle Zélande").map { it.number })
        assertEquals(listOf("112"), EmergencyNumbers.forCountries("Népal").map { it.number })
        assertEquals(false, EmergencyNumbers.isKnown("Népal"))
        assertEquals(true, EmergencyNumbers.isKnown("Grèce"))
    }
}
