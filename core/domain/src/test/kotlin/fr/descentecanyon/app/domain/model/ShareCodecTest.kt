package fr.descentecanyon.app.domain.model

import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareCodecTest {
    private val timing =
        SharedTiming(OutingSettings(prepMinutes = 30, mediumGroupFactor = 1.2, usePersonalPace = true), personalRatio = 1.1)

    private val outing = SharedOuting(
        canyonId = 2224,
        next = listOf(SharedNextCanyon(2225, "Aiglun", CanyonLink.ON_FOOT, mapOf(OutingPhase.APPROACH to 15))),
        date = LocalDate.of(2026, 7, 4),
        anchorTime = LocalTime.of(8, 30),
        meetAtPlace = true,
        place = SharedPlace("Camping des Gorges", 43.9, 6.9),
        groupSize = 6,
        groupLevel = GroupLevel.WITH_BEGINNERS,
        withShuttle = true,
        adjustments = mapOf(OutingPhase.DESCENT to 30),
        timing = timing,
        note = "Combinaisons 5 mm",
        summary = SharedOutingSummary(
            listOf("Riolan", "Aiglun"),
            LocalTime.of(8, 30),
            LocalTime.of(9, 5),
            LocalTime.of(16, 40),
            LocalTime.of(17, 30),
        ),
    )

    @Test
    fun `an outing goes through the server's JSON unchanged`() {
        val json = ShareCodec.encode(SharedContent.Outing(outing)).jsonObject
        assertEquals("08:30", json["anchorTime"]!!.jsonPrimitive.content)
        assertEquals("2026-07-04", json["date"]!!.jsonPrimitive.content)
        assertEquals("1", json["v"]!!.jsonPrimitive.content)
        assertEquals(SharedContent.Outing(outing), ShareCodec.decode(ShareCodec.KIND_OUTING, json))
    }

    @Test
    fun `an outing packed in a link comes back whole, and shorter than its JSON`() {
        val packed = ShareCodec.pack(outing)
        assertTrue(AppLinks.OUTING_PAYLOAD.matches(packed))
        assertTrue(packed.length < ShareCodec.encode(SharedContent.Outing(outing)).toString().length)
        assertEquals(outing, ShareCodec.unpack(packed))
        assertNull(ShareCodec.unpack("not-a-payload"))
        assertNull(ShareCodec.unpack("A".repeat(5000)))
    }

    @Test
    fun `a payload of a newer version, or of an unknown kind, is not read`() {
        val newer = Json.parseToJsonElement("""{"v":2,"canyonId":1,"anchorTime":"08:00","groupSize":4,"groupLevel":"AUTONOMOUS"}""")
        assertNull(ShareCodec.decode(ShareCodec.KIND_OUTING, newer))
        assertNull(ShareCodec.decode("PHOTO", ShareCodec.encode(SharedContent.Outing(outing))))
        // Unknown fields of a later version are ignored, an unknown step or link dropped.
        val later = Json.parseToJsonElement(
            """{"v":1,"canyonId":1,"anchorTime":"08:00","groupSize":4,"groupLevel":"EXPERTS","adjustments":{"NAP":5},"extra":true,
               "next":[{"canyonId":2,"link":"BY_PLANE"}]}""",
        )
        val read = (ShareCodec.decode(ShareCodec.KIND_OUTING, later) as SharedContent.Outing).outing
        assertEquals(GroupLevel.AUTONOMOUS, read.groupLevel)
        assertTrue(read.adjustments.isEmpty())
        assertTrue(read.next.isEmpty())
    }

    @Test
    fun `a stay's outings meet at the stay's place, a list keeps its canyons once`() {
        val stay = SharedStay(
            place = SharedPlace("Camping", 44.0, 6.0),
            start = LocalDate.of(2026, 7, 10),
            end = LocalDate.of(2026, 7, 14),
            outings = listOf(outing.copy(date = LocalDate.of(2026, 7, 11), place = SharedPlace("Camping", 44.0, 6.0))),
        )
        val json = ShareCodec.encode(SharedContent.Stay(stay)).jsonObject
        // In a stay, the place is said once.
        assertFalse((json["outings"] as kotlinx.serialization.json.JsonArray)[0].jsonObject.containsKey("place"))
        val read = (ShareCodec.decode(ShareCodec.KIND_STAY, json) as SharedContent.Stay).stay
        assertEquals(stay.place, read.outings.single().place)
        assertTrue(read.outings.single().meetAtPlace)
        val list = ShareCodec.decode(ShareCodec.KIND_LIST, Json.parseToJsonElement("""{"v":1,"title":"Stage","canyonIds":[3,1,3]}"""))
        assertEquals(SharedContent.CanyonList("Stage", listOf(3, 1)), list)
        assertNull(ShareCodec.decode(ShareCodec.KIND_LIST, JsonObject(emptyMap())))
    }

    @Test
    fun `the fingerprint changes with what the link would show`() {
        val first = ShareCodec.fingerprint(SharedContent.Outing(outing))
        assertEquals(first, ShareCodec.fingerprint(SharedContent.Outing(outing.copy())))
        assertNotEquals(first, ShareCodec.fingerprint(SharedContent.Outing(outing.copy(anchorTime = LocalTime.of(9, 0)))))
    }

    @Test
    fun `an outing added from a link keeps the sender's timing and meets at the shared place`() {
        val link = OutingLink("k3F9x2pQa", 2, isOwn = false)
        val choices = outing.toChoices(LocalDate.of(2026, 7, 1), placeId = 7, link = link)
        assertEquals(LocalDate.of(2026, 7, 4), choices.date)
        assertEquals(OutingAnchor.LEAVE_PLACE, choices.anchor)
        assertEquals(7L, choices.placeId)
        assertEquals(timing, choices.timing)
        assertEquals(link, choices.link)
        assertTrue(choices.isFromLink)
        assertEquals(listOf(NextCanyon(2225, "Aiglun", CanyonLink.ON_FOOT, mapOf(OutingPhase.APPROACH to 15))), choices.nextCanyons)
    }

    @Test
    fun `a starting place chosen for an outing met at the parking moves the departure, not the meeting`() {
        val atParking = outing.copy(
            meetAtPlace = false,
            place = null,
        ).toChoices(LocalDate.of(2026, 7, 1), null, OutingLink("abcdefgh", 1, false))
        assertEquals(OutingAnchor.ARRIVE_PARKING, atParking.anchor)
        val fromHome = atParking.withPlace(3)
        assertEquals(3L, fromHome.placeId)
        assertEquals(OutingAnchor.ARRIVE_PARKING, fromHome.anchor)
        // An outing of one's own: the time becomes the departure from the place.
        assertEquals(OutingAnchor.LEAVE_PLACE, OutingChoices(LocalDate.of(2026, 7, 1), LocalTime.of(8, 0)).withPlace(3).anchor)
    }

    @Test
    fun `a new version of the link keeps this phone's own choices`() {
        val link = OutingLink("abcdefgh", 1, false)
        val mine = outing.copy(meetAtPlace = false, place = null).toChoices(LocalDate.of(2026, 7, 1), null, link)
            .copy(placeId = 3, alertMarginHours = 3, alertDelayMinutes = 30, checkedGear = setOf("rope"))
        val newer = outing.copy(meetAtPlace = false, place = null, anchorTime = LocalTime.of(9, 15), groupSize = 8)
        val updated = newer.update(mine, null, link.copy(version = 2))
        assertEquals(LocalTime.of(9, 15), updated.anchorTime)
        assertEquals(8, updated.groupSize)
        assertEquals(3L, updated.placeId)
        assertEquals(3, updated.alertMarginHours)
        assertEquals(30, updated.alertDelayMinutes)
        assertEquals(setOf("rope"), updated.checkedGear)
        assertEquals(2, updated.link?.version)
    }
}
