package fr.descentecanyon.app.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutingSettingsTest {

    @Test
    fun `the group size and level pick their coefficients`() {
        val settings = OutingSettings()
        assertEquals(listOf(1.0, 1.0, 1.15, 1.15, 1.3, 1.3, 1.5), listOf(2, 4, 5, 6, 7, 8, 12).map(settings::groupFactor))
        assertEquals(1.3, settings.levelFactor(GroupLevel.WITH_BEGINNERS), 0.0)
        assertEquals(1.6, settings.levelFactor(GroupLevel.DISCOVERY), 0.0)
        assertEquals(1.0, settings.prepLevelFactor(GroupLevel.AUTONOMOUS), 0.0)
        assertEquals(1.5, settings.prepLevelFactor(GroupLevel.DISCOVERY), 0.0)

        val pace = OutingPace.of(settings, 7, GroupLevel.WITH_BEGINNERS, PersonalPace(1.1, 4))
        assertEquals(1.3 * 1.3 * 1.1, pace.descentFactor, 1e-9)
        assertEquals(1.1, pace.walkFactor, 0.0)
        assertEquals(1.3 * 1.5, pace.parkingFactor, 1e-9)
    }

    @Test
    fun `settings out of bounds are brought back, factors on steps of 0,05`() {
        val sanitized = OutingSettings(
            prepMinutes = -5,
            mediumGroupFactor = 0.4,
            largeGroupFactor = 1.337,
            laterPercent = 300,
            defaultGroupSize = 0,
        )
            .sanitized()
        assertEquals(0, sanitized.prepMinutes)
        assertEquals(1.0, sanitized.mediumGroupFactor, 0.0)
        assertEquals(1.35, sanitized.largeGroupFactor, 0.0)
        assertEquals(100, sanitized.laterPercent)
        assertEquals(1, sanitized.defaultGroupSize)
    }

    @Test
    fun `the personal pace is the median ratio of at least three timed outings`() {
        val threeHours = DurationRange(180, 180)
        assertNull(PersonalPace.of(listOf(200 to threeHours, 220 to threeHours)))
        assertEquals(
            PersonalPace(1.17, 3),
            PersonalPace.of(listOf(180 to threeHours, 210 to threeHours, 270 to threeHours, 5 to threeHours)),
        )
        // A range counts from its middle.
        assertEquals(PersonalPace(0.8, 3), PersonalPace.of(List(3) { 120 to DurationRange(120, 180) }))
        // Far beyond the sheet: kept within the bounds.
        assertEquals(PersonalPace(2.0, 3), PersonalPace.of(List(3) { 450 to threeHours }))
    }

    @Test
    fun `the drive is estimated offline from the straight line`() {
        // About 14 km in a straight line, 21 km of road at 45 km/h.
        val estimate = DriveTime.estimate(45.1015, 6.0794, 44.9853, 6.1505)
        assertEquals(DriveTimeSource.ESTIMATE, estimate.source)
        assertEquals(30, estimate.minutes)
    }

    @Test
    fun `the place by default is the one of the stay of the day, else the one used last`() {
        val home = Place("Maison", 45.0, 5.0, lastUsedAt = 10, id = 1)
        val campsite = Place("Camping", 44.0, 6.0, lastUsedAt = 5, id = 2)
        val places = listOf(home, campsite)
        val stays = listOf(Stay(placeId = 2, start = LocalDate.of(2026, 7, 12), end = LocalDate.of(2026, 7, 18), id = 1))

        assertEquals(campsite, places.defaultFor(LocalDate.of(2026, 7, 14), stays))
        assertEquals(home, places.defaultFor(LocalDate.of(2026, 7, 20), stays))
        // The campsite used last, but its stay is over: home stays the default.
        assertEquals(home, listOf(home, campsite.copy(lastUsedAt = 20)).defaultFor(LocalDate.of(2026, 7, 20), stays))
        assertNull(listOf(home.copy(lastUsedAt = 0)).defaultFor(LocalDate.of(2026, 7, 20), emptyList()))
    }

    @Test
    fun `a stay gives its days still to come and its outings`() {
        val stay = Stay(placeId = 2, start = LocalDate.of(2026, 7, 12), end = LocalDate.of(2026, 7, 16), id = 3)
        val start = Instant.parse("2026-07-15T07:00:00Z")
        fun outing(date: LocalDate, placeId: Long?, stayId: Long? = null) = PlannedOuting(
            canyonId = 1,
            canyonName = "Riolan",
            start = start,
            expectedBack = start,
            alertAt = start,
            choices = OutingChoices(date, LocalTime.of(9, 0)).withPlace(placeId),
            stayId = stayId,
        )

        assertEquals((14..16).map { LocalDate.of(2026, 7, it) }, stay.daysFrom(LocalDate.of(2026, 7, 14)))
        assertEquals(5, stay.daysFrom(LocalDate.of(2026, 7, 1)).size)
        assertTrue(stay.daysFrom(LocalDate.of(2026, 7, 17)).isEmpty())
        assertTrue(stay.includes(outing(LocalDate.of(2026, 7, 20), placeId = 9, stayId = 3)))
        assertTrue(stay.includes(outing(LocalDate.of(2026, 7, 15), placeId = 2)))
        assertFalse(stay.includes(outing(LocalDate.of(2026, 7, 15), placeId = 1)))
        assertFalse(stay.includes(outing(LocalDate.of(2026, 7, 15), placeId = 2, stayId = 4)))
    }

    @Test
    fun `the cars go to the end parking with a shuttle, else to the start one`() {
        val upstream = GeoPoint(canyonId = 1, type = GeoPointType.PARKING_AMONT, latitude = 44.0, longitude = 6.0)
        val downstream = GeoPoint(canyonId = 1, type = GeoPointType.PARKING_AVAL, latitude = 43.9, longitude = 6.1)
        val entry = GeoPoint(canyonId = 1, type = GeoPointType.ENTREE, latitude = 44.01, longitude = 6.01)
        val detail = CanyonDetail(canyon = canyon(), geoPoints = listOf(entry, downstream, upstream))

        assertEquals(downstream, detail.meetingParking(withShuttle = true))
        assertEquals(upstream, detail.meetingParking(withShuttle = false))
        assertEquals(downstream, detail.copy(geoPoints = listOf(downstream)).meetingParking(withShuttle = false))
        assertEquals(entry, detail.copy(geoPoints = listOf(entry)).meetingParking(withShuttle = true))
        assertNull(detail.copy(geoPoints = emptyList()).meetingParking(withShuttle = true))
    }

    @Test
    fun `the checklist adds what the outing calls for and the user's own items`() {
        val custom = GearItem.Custom("custom:1", GearGroup.SHARED, "Kit spit")
        val settings = GearListSettings(hidden = setOf(GearKey.KNIFE), custom = listOf(custom))
        val list = gearChecklist(settings, GearContext(ropeLengthMeters = 60, shuttleKm = 5, longestOnFootMinutes = 6 * 60))

        assertEquals(false, list.any { it is GearItem.Suggested && it.key == GearKey.KNIFE })
        assertEquals(GearItem.Hint(GearHint.FOOD_AND_WATER), list.first())
        val shared = list.filter { it.group == GearGroup.SHARED }
        assertEquals(GearItem.Hint(GearHint.ROPE, 60), shared[0])
        assertEquals(GearItem.Hint(GearHint.SHUTTLE_CARS, 5), shared[1])
        assertEquals(custom, shared.last())
        assertEquals("gear:HELMET", GearItem.Suggested(GearKey.HELMET).id)

        val short = gearChecklist(GearListSettings(), GearContext(longestOnFootMinutes = 90))
        assertEquals(false, short.any { it is GearItem.Hint })
    }

    private fun canyon() =
        Canyon(id = 1, nom = "Riolan", nomComplet = "Riolan", pays = "France", commune = "Sigale", cotation = "v4", url = "")
}
