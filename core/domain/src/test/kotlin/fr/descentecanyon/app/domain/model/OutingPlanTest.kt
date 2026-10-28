package fr.descentecanyon.app.domain.model

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutingPlanTest {

    private val day = LocalDate.of(2026, 7, 14)
    private val settings = OutingSettings()
    private fun at(hour: Int, minute: Int) = LocalDateTime.of(day, LocalTime.of(hour, minute))
    private fun window(earliest: LocalDateTime, estimate: LocalDateTime, latest: LocalDateTime) = TimeWindow(earliest, estimate, latest)

    private fun schedule(
        approach: String = "30min",
        descent: String = "3h",
        back: String = "10min",
        anchor: OutingAnchor = OutingAnchor.ARRIVE_PARKING,
        anchorTime: LocalTime = LocalTime.of(9, 0),
        driveMinutes: Int? = null,
        shuttle: Shuttle? = null,
        groupSize: Int = 4,
        level: GroupLevel = GroupLevel.AUTONOMOUS,
        pace: PersonalPace? = null,
        settings: OutingSettings = this.settings,
        adjustments: Map<OutingPhase, Int> = emptyMap(),
    ) = OutingSchedule.compute(
        OutingScheduleInput(
            date = day,
            anchor = anchor,
            anchorTime = anchorTime,
            driveMinutes = driveMinutes,
            shuttle = shuttle,
            approach = CanyonDuration.parse(approach),
            descent = CanyonDuration.parse(descent),
            back = CanyonDuration.parse(back),
            pace = OutingPace.of(settings, groupSize, level, pace),
            settings = settings,
            adjustments = adjustments,
        ),
    )

    @Test
    fun `a sheet time becomes a range around it, and a range keeps its middle as the estimate`() {
        assertEquals(TimeSpan(162, 180, 225), TimeSpan.fromSheet(DurationRange(180, 180), 1.0, 10, 25))
        assertEquals(TimeSpan(18, 33, 56), TimeSpan.fromSheet(DurationRange(20, 45), 1.0, 10, 25))
        assertEquals(TimeSpan(0, 5, 20), TimeSpan(10, 20, 35).adjustedBy(-15))
    }

    @Test
    fun `a small autonomous group adds the gearing up to the sheet`() {
        val schedule = schedule()

        assertEquals(at(9, 0), schedule.arriveParking)
        assertEquals(at(9, 20), schedule.walkStart)
        assertEquals(window(at(9, 47), at(9, 50), at(9, 58)), schedule.descentStart)
        assertEquals(window(at(12, 29), at(12, 50), at(13, 43)), schedule.descentEnd)
        assertEquals(window(at(12, 38), at(13, 0), at(13, 56)), schedule.backAtCar)
        assertEquals(window(at(12, 58), at(13, 20), at(14, 16)), schedule.readyToLeave)
        assertNull(schedule.leavePlace)
        assertNull(schedule.backAtPlace)
    }

    @Test
    fun `a day from a campsite with a shuttle and a group with beginners`() {
        // Campsite 35 min away, shuttle of 5 km, six people with beginners, sheet 30 min / 3 h / 10 min.
        val schedule = schedule(
            anchor = OutingAnchor.LEAVE_PLACE,
            anchorTime = LocalTime.of(8, 0),
            driveMinutes = 35,
            shuttle = Shuttle(5.0),
            groupSize = 6,
            level = GroupLevel.WITH_BEGINNERS,
        )

        assertEquals(at(8, 0), schedule.leavePlace)
        assertEquals(at(8, 35), schedule.arriveParking)
        // Shuttle: 10 min of road and 10 min to leave the car.
        assertEquals(at(8, 55), schedule.arriveStartParking)
        // Gearing up: 20 min × 1.15 (six people) × 1.5 (beginners).
        assertEquals(at(9, 30), schedule.walkStart)
        // Descent: 3 h × 1.15 × 1.3.
        assertEquals(TimeSpan(242, 269, 336), schedule.spans[OutingPhase.DESCENT])
        assertEquals(window(at(14, 8), at(14, 39), at(15, 57)), schedule.backAtCar)
        // Packing (35 min) and fetching the car at the start (10 min).
        assertEquals(window(at(14, 53), at(15, 24), at(16, 42)), schedule.readyToLeave)
        assertEquals(window(at(15, 28), at(15, 59), at(17, 17)), schedule.backAtPlace)
    }

    @Test
    fun `the meeting at the parking gives the time to leave the place`() {
        val schedule = schedule(anchorTime = LocalTime.of(9, 0), driveMinutes = 50)

        assertEquals(OutingAnchor.ARRIVE_PARKING, schedule.anchor)
        assertEquals(at(8, 10), schedule.leavePlace)
        assertEquals(at(9, 0), schedule.arriveParking)

        // Without a place, leaving it means nothing: the time is the arrival at the parking.
        val noPlace = schedule(anchor = OutingAnchor.LEAVE_PLACE, anchorTime = LocalTime.of(9, 0))
        assertEquals(OutingAnchor.ARRIVE_PARKING, noPlace.anchor)
        assertEquals(at(9, 0), noPlace.arriveParking)
    }

    @Test
    fun `the personal pace stretches the walks and the descent, the adjustments move one step`() {
        val slow = schedule(pace = PersonalPace(1.2, 5))
        assertEquals(TimeSpan(32, 36, 45), slow.spans[OutingPhase.APPROACH])
        assertEquals(TimeSpan(194, 216, 270), slow.spans[OutingPhase.DESCENT])

        val ignored = schedule(pace = PersonalPace(1.2, 5), settings = settings.copy(usePersonalPace = false))
        assertEquals(TimeSpan(27, 30, 38), ignored.spans[OutingPhase.APPROACH])

        val adjusted = schedule(adjustments = mapOf(OutingPhase.PREP to 15, OutingPhase.DESCENT to -30))
        assertEquals(at(9, 35), adjusted.walkStart)
        assertEquals(TimeSpan(132, 150, 195), adjusted.spans[OutingPhase.DESCENT])
    }

    @Test
    fun `no approach counts as zero and an unknown step stops the schedule`() {
        val noApproach = schedule(approach = "néant", descent = "2h", back = "??")
        assertEquals(TimeWindow.at(noApproach.walkStart), noApproach.descentStart)
        assertNull(noApproach.backAtCar)
        assertNull(noApproach.longestOnFoot)

        val unknownDescent = schedule(descent = "??")
        assertNull(unknownDescent.descentEnd)
        assertNull(unknownDescent.backAtCar)
    }

    @Test
    fun `daylight is fine with half an hour left even when late, else the anchor must move`() {
        val morning = schedule()
        val ok = DaylightCheck.of(morning, LocalTime.of(21, 0)) as DaylightCheck.Ok
        assertEquals(Duration.ofMinutes(7 * 60 + 4), ok.margin)
        // Latest return 13:56 from a descent started at 9:58 at worst: the descent must start by 16:32.
        assertEquals(LocalTime.of(16, 32), ok.latestDescentStart)

        val afternoon = schedule(anchorTime = LocalTime.of(15, 0))
        // At worst back at 19:56; with sunset at 19:00 the parking must be reached by 13:34.
        assertEquals(DaylightCheck.Late(LocalTime.of(19, 0), LocalTime.of(13, 34)), DaylightCheck.of(afternoon, LocalTime.of(19, 0)))

        assertEquals(DaylightCheck.Unknown, DaylightCheck.of(morning, null))
    }

    @Test
    fun `the shuttle of a sheet is read in km`() {
        assertEquals(Shuttle(3.0), Shuttle.parse("3.0km"))
        assertEquals(Shuttle(2.5), Shuttle.parse("2,5 km"))
        assertNull(Shuttle.parse("néant"))
        assertNull(Shuttle.parse("??"))
        assertNull(Shuttle.parse(null))
        assertEquals(10, Shuttle(5.0).driveMinutes)
        assertEquals(5, Shuttle(1.0).driveMinutes)
    }

    @Test
    fun `an outing is under way from shortly before its start or from leaving the place`() {
        val start = Instant.parse("2026-07-14T08:00:00Z")
        val outing = PlannedOuting(42, "Riolan", start, start.plus(Duration.ofHours(6)), start.plus(Duration.ofHours(8)))

        assertEquals(false, outing.isUnderway(start.minus(Duration.ofHours(3))))
        assertEquals(true, outing.isUnderway(start.minus(Duration.ofHours(1))))
        assertEquals(true, outing.isUnderway(start.plus(Duration.ofHours(10))))
        assertEquals(false, outing.isUnderway(start.plus(Duration.ofHours(21))))

        val farAway = outing.copy(departure = start.minus(Duration.ofHours(3)))
        assertEquals(true, farAway.isUnderway(start.minus(Duration.ofHours(3))))
    }

    @Test
    fun `the outing under way is the one started last, the others are to come`() {
        val now = Instant.parse("2026-07-14T12:00:00Z")
        val morning = PlannedOuting(1, "Matin", now.minus(Duration.ofHours(4)), now, now.plus(Duration.ofHours(2)), id = 1)
        val afternoon =
            PlannedOuting(
                2,
                "Après-midi",
                now.plus(Duration.ofHours(1)),
                now.plus(Duration.ofHours(4)),
                now.plus(Duration.ofHours(6)),
                id = 2,
            )
        val tomorrow =
            PlannedOuting(
                3,
                "Demain",
                now.plus(Duration.ofHours(21)),
                now.plus(Duration.ofHours(26)),
                now.plus(Duration.ofHours(28)),
                id = 3,
            )
        val outings = listOf(tomorrow, morning, afternoon)

        assertEquals(afternoon, outings.underwayAt(now))
        assertEquals(listOf(tomorrow), outings.upcomingAt(now))
        assertEquals(listOf(afternoon, tomorrow), outings.upcomingAt(now.minus(Duration.ofHours(3))))
    }

    @Test
    fun `an outing moved by days keeps its times of day, across a clock change`() {
        val zone = ZoneId.of("Europe/Paris")
        val start = LocalDateTime.of(2026, 10, 23, 9, 0).atZone(zone).toInstant()
        val outing = PlannedOuting(
            1,
            "Riolan",
            start,
            start.plus(Duration.ofHours(5)),
            start.plus(Duration.ofHours(7)),
            choices = OutingChoices(LocalDate.of(2026, 10, 23), LocalTime.of(9, 0)),
        )

        val moved = outing.movedBy(3, zone)

        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 0), moved.start.atZone(zone).toLocalDateTime())
        assertEquals(LocalDateTime.of(2026, 10, 26, 16, 0), moved.alertAt.atZone(zone).toLocalDateTime())
        assertEquals(LocalDate.of(2026, 10, 26), moved.choices?.date)
    }

    @Test
    fun `the relative is told again when the alert moves`() {
        val now = Instant.parse("2026-07-14T08:00:00Z")
        val outing = PlannedOuting(1, "Riolan", now, now.plus(Duration.ofHours(4)), now.plus(Duration.ofHours(6)))
        assertFalse(outing.relativeInformed)
        assertFalse(outing.alertChangedSinceInformed)

        val informed = outing.copy(informedAlertAt = outing.alertAt)
        assertTrue(informed.relativeInformed)
        assertFalse(informed.alertChangedSinceInformed)
        assertTrue(informed.copy(alertAt = outing.alertAt.plus(Duration.ofMinutes(30))).alertChangedSinceInformed)
    }

    @Test
    fun `the agenda is given again when an outing gets under way`() = runTest {
        val start = Instant.parse("2026-07-14T08:00:00Z")
        val origin = start.minus(Duration.ofHours(3))
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId?): Clock = this
            override fun instant(): Instant = origin.plusMillis(testScheduler.currentTime)
        }
        val outing = PlannedOuting(42, "Riolan", start, start.plus(Duration.ofHours(4)), start.plus(Duration.ofHours(6)), id = 1)

        val agendas = flowOf(listOf(outing)).agenda(clock).take(2).toList()

        assertEquals(listOf(OutingAgenda(upcoming = listOf(outing)), OutingAgenda(underway = outing)), agendas)
        assertEquals(Duration.ofHours(1).toMillis(), testScheduler.currentTime)
    }
}
