package fr.descentecanyon.app.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Several canyons in one outing: an "intégrale" straight on or on foot, or a drive between two canyons. */
class OutingChainTest {

    private val day = LocalDate.of(2026, 7, 14)
    private val settings = OutingSettings()
    private fun at(hour: Int, minute: Int) = LocalDateTime.of(day, LocalTime.of(hour, minute))

    /** The upper part of the Pissarde, from the parking at 9:00 (two cars, 2 km of shuttle), then [next]. */
    private fun upperPartThen(vararg next: NextCanyonInput) = OutingSchedule.compute(
        OutingScheduleInput(
            date = day,
            anchor = OutingAnchor.ARRIVE_PARKING,
            anchorTime = LocalTime.of(9, 0),
            driveMinutes = null,
            shuttle = Shuttle(2.0),
            approach = CanyonDuration.parse("20min"),
            descent = CanyonDuration.parse("1h15 à 1h45"),
            back = CanyonDuration.parse("5min"),
            pace = OutingPace.of(settings, 4, GroupLevel.AUTONOMOUS, null),
            settings = settings,
            nextCanyons = next.toList(),
        ),
    )

    private fun lowerPart(
        link: CanyonLink = CanyonLink.ON_FOOT,
        driveMinutes: Int? = null,
        adjustments: Map<OutingPhase, Int> = emptyMap(),
    ) = NextCanyonInput(
        link = link,
        approach = CanyonDuration.parse("5min"),
        descent = CanyonDuration.parse("1h15"),
        back = CanyonDuration.parse("2min"),
        driveMinutes = driveMinutes,
        adjustments = adjustments,
    )

    @Test
    fun `on foot, the lower part starts from the end of the upper one and the day ends after it`() {
        val schedule = upperPartThen(lowerPart())

        // 9:00 at the lower parking, 15 min of shuttle, 20 min to get ready, 20 min of approach.
        assertEquals(at(9, 55), schedule.descentStart?.estimate)
        assertEquals(TimeWindow(at(11, 1), at(11, 25), at(12, 11)), schedule.descentEnd)
        val lower = schedule.nextCanyons.single()
        // Out of the upper part (5 min), then down to the lower one (5 min).
        assertEquals(TimeWindow(at(11, 6), at(11, 30), at(12, 17)), lower.walkStart)
        assertEquals(TimeWindow(at(11, 11), at(11, 35), at(12, 23)), lower.descentStart)
        assertEquals(TimeWindow(at(12, 19), at(12, 50), at(13, 57)), schedule.lastDescentEnd)
        assertEquals(TimeWindow(at(12, 21), at(12, 52), at(14, 0)), schedule.backAtCar)
        assertNull(lower.transfer)
        assertNull(lower.backAtCarBefore)
    }

    @Test
    fun `by car, the group packs up, drives and gets ready again before the next canyon`() {
        val lower = upperPartThen(lowerPart(CanyonLink.BY_CAR, driveMinutes = 15)).nextCanyons.single()

        assertEquals(TimeWindow(at(11, 6), at(11, 30), at(12, 17)), lower.backAtCarBefore)
        // 20 min to pack up, 15 min of road, 20 min to get ready.
        assertEquals(TimeSpan.exactly(55), lower.transfer)
        assertEquals(TimeWindow(at(12, 1), at(12, 25), at(13, 12)), lower.walkStart)
        assertEquals(at(12, 30), lower.descentStart?.estimate)
    }

    @Test
    fun `straight on, the lower part starts at the end of the upper one, with no way out nor way in`() {
        val schedule = upperPartThen(lowerPart(CanyonLink.CONTINUOUS))

        val lower = schedule.nextCanyons.single()
        assertEquals(schedule.descentEnd, lower.walkStart)
        assertEquals(schedule.descentEnd, lower.descentStart)
        assertEquals(TimeSpan.ZERO, lower.spans[OutingPhase.APPROACH])
        // Ten minutes earlier than on foot: neither the 5 min out of the upper part nor the 5 min down to the lower one.
        assertEquals(TimeWindow(at(12, 11), at(12, 42), at(13, 48)), schedule.backAtCar)
    }

    @Test
    fun `straight on, the time from one canyon to the other is adjusted, and an approach unknown on the sheet does not matter`() {
        val lower = upperPartThen(
            lowerPart(CanyonLink.CONTINUOUS, adjustments = mapOf(OutingPhase.APPROACH to 10)).copy(approach = CanyonDuration.Unknown),
        ).nextCanyons.single()

        assertEquals(TimeSpan.exactly(10), lower.spans[OutingPhase.APPROACH])
        assertEquals(at(11, 35), lower.descentStart?.estimate)
    }

    @Test
    fun `a walk the topos give replaces the way out of one canyon and the way in to the next`() {
        val lower = upperPartThen(lowerPart(CanyonLink.ON_FOOT).copy(betweenMinutes = 45)).nextCanyons.single()

        assertEquals(true, lower.direct)
        assertEquals(TimeSpan.exactly(45), lower.spans[OutingPhase.APPROACH])
        // From the end of the upper part (11:25), 45 min on foot: neither its return nor the sheet's approach.
        assertEquals(at(12, 10), lower.descentStart?.estimate)
        // Straight on with a time between the two parts, such as a long walk in the stream bed.
        val straightOn = upperPartThen(lowerPart(CanyonLink.CONTINUOUS).copy(betweenMinutes = 180)).nextCanyons.single()
        assertEquals(at(14, 25), straightOn.descentStart?.estimate)
        // By car, the topos' walk does not count.
        assertEquals(
            false,
            upperPartThen(lowerPart(CanyonLink.BY_CAR, driveMinutes = 15).copy(betweenMinutes = 45)).nextCanyons.single().direct,
        )
    }

    @Test
    fun `a step of the next canyon is adjusted on its own`() {
        val schedule = upperPartThen(lowerPart(adjustments = mapOf(OutingPhase.DESCENT to 15)))

        assertEquals(TimeSpan(83, 90, 109), schedule.nextCanyons.single().spans[OutingPhase.DESCENT])
        assertEquals(at(13, 7), schedule.backAtCar?.estimate)
        // The first canyon keeps its own times.
        assertEquals(TimeSpan(68, 90, 131), schedule.spans[OutingPhase.DESCENT])
    }

    @Test
    fun `a next canyon whose sheet gives no descent time leaves the return unknown`() {
        val schedule = upperPartThen(lowerPart().copy(descent = CanyonDuration.Unknown))

        assertEquals(at(11, 35), schedule.nextCanyons.single().descentStart?.estimate)
        assertNull(schedule.lastDescentEnd)
        assertNull(schedule.backAtCar)
    }

    @Test
    fun `the outing names its canyons and knows which one the group is in`() {
        val lowerFrom = Instant.parse("2026-07-14T09:25:00Z")
        val outing = PlannedOuting(
            canyonId = 24,
            canyonName = "Pissarde (partie amont)",
            start = Instant.parse("2026-07-14T07:35:00Z"),
            expectedBack = Instant.parse("2026-07-14T10:52:00Z"),
            alertAt = Instant.parse("2026-07-14T14:00:00Z"),
            choices = OutingChoices(
                date = day,
                anchorTime = LocalTime.of(9, 0),
                nextCanyons = listOf(NextCanyon(25, "Pissarde (partie aval)", from = lowerFrom)),
            ),
        )

        assertEquals("Pissarde (partie amont) + Pissarde (partie aval)", outing.title)
        assertEquals(listOf(24, 25), outing.canyonIds)
        assertEquals(24, outing.canyonIdAt(lowerFrom.minusSeconds(60)))
        assertEquals(25, outing.canyonIdAt(lowerFrom))
    }
}
