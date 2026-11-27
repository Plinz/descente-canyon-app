package fr.descentecanyon.app.data.repository

import android.app.Application
import fr.descentecanyon.app.data.remote.backend.BackendTestServer
import fr.descentecanyon.app.data.remote.backend.TrackDto
import fr.descentecanyon.app.data.remote.backend.TrackListDto
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.toAppError
import fr.descentecanyon.app.domain.model.TrackRejection
import fr.descentecanyon.app.domain.model.TrackRole
import fr.descentecanyon.app.domain.model.TrackStanding
import fr.descentecanyon.app.domain.model.TrackVote
import fr.descentecanyon.app.testutil.TestDatabases
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** tracks.json is served by the Python server (server/tools/make_app_fixtures.py). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CommunityTrackRepositoryImplTest {

    private val context = TestDatabases.context
    private val backend = BackendTestServer()
    private val database = TestDatabases.user()
    private val clock = Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC)
    private val listing = backend.json.decodeFromString(TrackListDto.serializer(), BackendTestServer.resource("backend/tracks.json"))

    private fun repository(clock: Clock = this.clock) = CommunityTrackRepositoryImpl(
        context,
        backend.client,
        database.keyValueDao(),
        InstallKeyStore(database.keyValueDao()),
        backend.json,
        clock,
        Dispatchers.Unconfined,
    )

    @After
    fun tearDown() {
        backend.shutdown()
        database.close()
        File(context.noBackupFilesDir, "community-tracks").deleteRecursively()
    }

    @Test
    fun `tracks read from the server are mapped in rank order and kept for offline use`() = runTest {
        backend.server.enqueue(json(BackendTestServer.resource("backend/tracks.json")))

        val tracks = repository().refresh(21).getOrThrow()

        assertEquals(listOf(TrackRole.APPROACH, TrackRole.RETURN), tracks.tracks.map { it.role })
        val reference = tracks.tracks.first()
        assertTrue(reference.isReference)
        assertEquals(TrackStanding.ENDORSED, reference.standing)
        assertEquals(3, reference.upVotes)
        assertEquals(0.4385, reference.score, 1e-6)
        assertEquals(YearMonth.of(2026, 6), reference.recordedMonth)
        assertEquals(44.868481, reference.segments.first().first().latitude, 1e-6)
        assertEquals(5.5616, reference.segments.first().first().longitude, 1e-6)
        assertEquals(700.0, reference.segments.first().first().elevation!!, 1e-6)
        assertEquals(TrackStanding.DISPUTED, tracks.tracks[1].standing)
        assertNull(tracks.tracks[1].recordedMonth)
        assertFalse(reference.isMine)
        assertEquals(TrackVote.NONE, reference.myVote)
        assertEquals("/v1/canyons/21/tracks", backend.server.takeRequest().path)

        val offline = repository().observeTracks(21).first()
        assertEquals(tracks.tracks.map { it.id }, offline?.tracks?.map { it.id })
        assertEquals(clock.instant(), offline?.fetchedAt)
    }

    @Test
    fun `an upload sends the file with the installation key, the track becomes mine`() = runTest {
        val created = listing.tracks.first()
        backend.server.enqueue(json(BackendTestServer.resource("backend/tracks.json")))
        backend.server.enqueue(json(backend.json.encodeToString(TrackDto.serializer(), created), code = 201))
        backend.server.enqueue(json(backend.json.encodeToString(TrackDto.serializer(), listing.tracks[1].copy(up = 1))))
        val repository = repository()
        repository.refresh(21).getOrThrow()
        backend.server.takeRequest()

        val track = repository.upload(21, TrackRole.APPROACH, "<gpx/>".toByteArray()).getOrThrow()
        val upload = backend.server.takeRequest()
        val voted = repository.vote(
            repository.observeTracks(21).first()!!.tracks.first {
                it.id == listing.tracks[1].id
            },
            TrackVote.UP,
        ).getOrThrow()
        val vote = backend.server.takeRequest()

        assertEquals("POST", upload.method)
        assertEquals("/v1/canyons/21/tracks?role=APPROACH", upload.path)
        assertEquals("<gpx/>", upload.body.readUtf8())
        assertEquals("application/gpx+xml", upload.getHeader("Content-Type")?.substringBefore(';'))
        val key = upload.getHeader("X-Install-Key")
        assertEquals(43, key?.length)
        assertTrue(track.isMine)
        assertEquals("PUT", vote.method)
        assertEquals(1, JSONObject(vote.body.readUtf8()).getInt("value"))
        assertEquals(key, vote.getHeader("X-Install-Key"))
        assertEquals(TrackVote.UP, voted.myVote)
        assertEquals(TrackVote.UP, repository.observeTracks(21).first()!!.tracks.first { it.id == voted.id }.myVote)
    }

    @Test
    fun `plain reads carry no installation key`() = runTest {
        backend.server.enqueue(json(BackendTestServer.resource("backend/tracks.json")))

        repository().refresh(21).getOrThrow()

        assertNull(backend.server.takeRequest().getHeader("X-Install-Key"))
    }

    @Test
    fun `refusals of the server become typed errors`() = runTest {
        val repository = repository()
        backend.server.enqueue(json(BackendTestServer.resource("backend/error-invalid-gpx.json"), code = 422))
        backend.server.enqueue(json("""{"error":"RATE_LIMITED"}""", code = 429))
        backend.server.enqueue(json(BackendTestServer.resource("backend/tracks.json")))
        backend.server.enqueue(json("""{"error":"NOT_FOUND"}""", code = 404))

        val invalid = repository.upload(21, TrackRole.RETURN, "<gpx".toByteArray()).exceptionOrNull()?.toAppError()
        val limited = repository.upload(21, TrackRole.RETURN, "<gpx/>".toByteArray()).exceptionOrNull()?.toAppError()
        repository.refresh(21).getOrThrow()
        val gone = repository.observeTracks(21).first()!!.tracks.first()
        val missing = repository.delete(gone).exceptionOrNull()?.toAppError()

        assertEquals(AppError.TrackRejected(TrackRejection.INVALID_GPX), invalid)
        assertEquals(AppError.TrackRejected(TrackRejection.RATE_LIMITED), limited)
        assertEquals(AppError.NotFound, missing)
    }

    @Test
    fun `prefetch reads the canyons never read or read long ago, the ones the server does not know are skipped`() = runTest {
        backend.server.enqueue(json(BackendTestServer.resource("backend/tracks.json")))
        repository().refresh(21).getOrThrow()
        backend.server.takeRequest()
        backend.server.enqueue(json("""{"canyonId":22,"tracks":[]}"""))
        backend.server.enqueue(json("""{"error":"UNKNOWN_CANYON"}""", code = 404))

        // 21 was just read: only 22 and 23, each once.
        val read = repository().prefetch(listOf(21, 22, 23, 22), Duration.ofHours(12)).getOrThrow()

        assertEquals(1, read)
        assertEquals("/v1/canyons/22/tracks", backend.server.takeRequest().path)
        assertEquals("/v1/canyons/23/tracks", backend.server.takeRequest().path)
        assertEquals(3, backend.server.requestCount)
        // Kept for the canyon, out of network.
        assertEquals(listing.tracks.size, repository().observeTracks(21).first()?.tracks?.size)
        assertEquals(emptyList<String>(), repository().observeTracks(22).first()?.tracks?.map { it.id })

        // Thirteen hours later, 21 and 22 are read again.
        backend.server.enqueue(json(BackendTestServer.resource("backend/tracks.json")))
        backend.server.enqueue(json("""{"canyonId":22,"tracks":[]}"""))
        assertEquals(2, repository(Clock.offset(clock, Duration.ofHours(13))).prefetch(listOf(21, 22), Duration.ofHours(12)).getOrThrow())
    }

    @Test
    fun `prefetch stops at a failure of the network`() = runTest {
        backend.server.enqueue(MockResponse().setResponseCode(503))

        val error = repository().prefetch(listOf(21, 22), Duration.ofHours(12)).exceptionOrNull()?.toAppError()

        assertEquals(AppError.Http(503), error)
        assertEquals(1, backend.server.requestCount)
    }

    @Test
    fun `a file over 2 MB is refused before any request`() = runTest {
        val error = repository().upload(21, TrackRole.FULL, ByteArray(2 * 1024 * 1024 + 1)).exceptionOrNull()?.toAppError()

        assertEquals(AppError.TrackRejected(TrackRejection.TOO_LARGE), error)
        assertEquals(0, backend.server.requestCount)
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
}
