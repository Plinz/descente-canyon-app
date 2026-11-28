package fr.descentecanyon.app.data.repository

import android.app.Application
import fr.descentecanyon.app.data.remote.backend.BackendTestServer
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.toAppError
import fr.descentecanyon.app.domain.model.AppServerState
import fr.descentecanyon.app.domain.model.CanyonLink
import fr.descentecanyon.app.domain.model.GroupLevel
import fr.descentecanyon.app.domain.model.OutingPhase
import fr.descentecanyon.app.domain.model.OutingSettings
import fr.descentecanyon.app.domain.model.ServerFeatures
import fr.descentecanyon.app.domain.model.SharedContent
import fr.descentecanyon.app.domain.model.SharedNextCanyon
import fr.descentecanyon.app.domain.model.SharedOuting
import fr.descentecanyon.app.domain.model.SharedOutingSummary
import fr.descentecanyon.app.domain.model.SharedPlace
import fr.descentecanyon.app.domain.model.SharedTiming
import fr.descentecanyon.app.domain.repository.AppServerRepository
import fr.descentecanyon.app.testutil.TestDatabases
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The share fixtures are written by the Python server (server/tools/make_share_fixtures.py). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShareRepositoryImplTest {
    private val backend = BackendTestServer()
    private val database = TestDatabases.user()
    private val serverState = MutableStateFlow(AppServerState(isConfigured = true, features = ServerFeatures(shares = true)))
    private val appServer = mockk<AppServerRepository> { every { observeState() } returns serverState }
    private val repository = ShareRepositoryImpl(backend.client, backend.config, InstallKeyStore(database.keyValueDao()), appServer)

    /** The outing behind share-request.json. */
    private val outing = SharedOuting(
        canyonId = 2224,
        next = listOf(SharedNextCanyon(2225, "Aiglun", CanyonLink.ON_FOOT, mapOf(OutingPhase.APPROACH to 15))),
        date = LocalDate.of(2027, 7, 3),
        anchorTime = LocalTime.of(8, 30),
        meetAtPlace = true,
        place = SharedPlace("Camping des Gorges", 43.9, 6.9),
        groupSize = 6,
        groupLevel = GroupLevel.WITH_BEGINNERS,
        withShuttle = true,
        adjustments = mapOf(OutingPhase.DESCENT to 30),
        timing = SharedTiming(OutingSettings(prepMinutes = 30, mediumGroupFactor = 1.2, usePersonalPace = true), personalRatio = 1.1),
        note = "Combinaisons 5 mm",
        summary = SharedOutingSummary(
            listOf("Riolan", "Aiglun"),
            LocalTime.of(8, 30),
            LocalTime.of(9, 5),
            LocalTime.of(16, 40),
            LocalTime.of(17, 30),
        ),
    )

    @After
    fun tearDown() {
        backend.shutdown()
        database.close()
    }

    @Test
    fun `an outing is sent as the server accepts it, and its short link comes back`() = runTest {
        backend.server.enqueue(MockResponse().setResponseCode(201).setBody(BackendTestServer.resource("backend/share.json")))

        val published = repository.publish(SharedContent.Outing(outing)).getOrThrow()

        assertEquals("4ECKQHyww", published.code)
        assertEquals("https://canyon.aduquennoy.fr/s/4ECKQHyww", published.url)
        val request = backend.server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/shares", request.path)
        assertTrue(request.getHeader("X-Install-Key")!!.length == 43)
        assertEquals(
            Json.parseToJsonElement(BackendTestServer.resource("backend/share-request.json")),
            Json.parseToJsonElement(request.body.readUtf8()),
        )
    }

    @Test
    fun `this phone's link is updated, and made again when it expired`() = runTest {
        backend.server.enqueue(
            MockResponse().setResponseCode(404).setBody(BackendTestServer.resource("backend/error-share-not-found.json")),
        )
        backend.server.enqueue(MockResponse().setResponseCode(201).setBody(BackendTestServer.resource("backend/share.json")))

        val published = repository.publish(SharedContent.Outing(outing), code = "oldCode99").getOrThrow()

        assertEquals("4ECKQHyww", published.code)
        assertEquals("PUT /v1/shares/oldCode99", backend.server.takeRequest().let { "${it.method} ${it.path}" })
        assertEquals("POST /v1/shares", backend.server.takeRequest().let { "${it.method} ${it.path}" })
    }

    @Test
    fun `a link is read back whole, without any key`() = runTest {
        backend.server.enqueue(MockResponse().setBody(BackendTestServer.resource("backend/share-read.json")))

        val received = repository.fetch("4ECKQHyww").getOrThrow()

        assertEquals(1, received.version)
        assertEquals(SharedContent.Outing(outing), received.content)
        assertEquals(null, backend.server.takeRequest().getHeader("X-Install-Key"))
    }

    @Test
    fun `a link that does not exist, and no link made without the server's feature`() = runTest {
        backend.server.enqueue(
            MockResponse().setResponseCode(404).setBody(BackendTestServer.resource("backend/error-share-not-found.json")),
        )
        assertEquals(AppError.NotFound, repository.fetch("zzzzzzzzz").exceptionOrNull()?.toAppError())

        serverState.value = AppServerState(isConfigured = true, features = ServerFeatures(shares = false))
        assertEquals(AppError.DataUnavailable, repository.publish(SharedContent.Outing(outing)).exceptionOrNull()?.toAppError())
        assertEquals(0, backend.server.requestCount - 1)
    }
}
