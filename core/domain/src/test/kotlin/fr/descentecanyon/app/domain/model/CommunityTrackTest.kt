package fr.descentecanyon.app.domain.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityTrackTest {

    private val track = CommunityTrack(
        id = "t1",
        canyonId = 21,
        role = TrackRole.APPROACH,
        createdAt = Instant.parse("2026-11-25T10:00:00Z"),
        recordedMonth = null,
        lengthMeters = 1200,
        ascentMeters = 150,
        descentMeters = 0,
        segments = listOf(listOf(TrackPoint(44.868481, 5.5616, 700.0), TrackPoint(44.8689, 5.5619))),
        bounds = GeoBounds(5.5616, 44.868481, 5.5619, 44.8689),
        upVotes = 0,
        downVotes = 0,
        standing = TrackStanding.NEW,
        isReference = false,
        isMine = false,
        myVote = TrackVote.NONE,
    )

    @Test
    fun `the GPX export holds the geometry and an escaped title, no time`() {
        val gpx = track.toGpx("Mouna & Pellas – <Approche>")

        assertTrue(gpx.contains("<name>Mouna &amp; Pellas – &lt;Approche&gt;</name>"))
        assertTrue(gpx.contains("""<trkpt lat="44.868481" lon="5.561600"><ele>700.0</ele></trkpt>"""))
        assertTrue(gpx.contains("""<trkpt lat="44.868900" lon="5.561900"/>"""))
        assertTrue(!gpx.contains("<time>"))
    }

    @Test
    fun `discredited tracks are kept apart`() {
        val tracks = CommunityTracks(21, listOf(track, track.copy(id = "t2", standing = TrackStanding.DISCREDITED)), Instant.EPOCH)

        assertEquals(listOf("t1"), tracks.visible.map { it.id })
        assertEquals(listOf("t2"), tracks.discredited.map { it.id })
    }

    @Test
    fun `a server message falls back to English, then French`() {
        val message = ServerMessage("m", ServerMessageLevel.INFO, mapOf("fr" to "Bonjour", "en" to "Hello"))

        assertEquals("Bonjour", message.textFor("fr"))
        assertEquals("Hello", message.textFor("it"))
        assertEquals("Bonjour", ServerMessage("m", ServerMessageLevel.INFO, mapOf("fr" to "Bonjour")).textFor("it"))
    }
}
