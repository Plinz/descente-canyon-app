package fr.descentecanyon.app.data.repository

import android.app.Application
import fr.descentecanyon.app.data.AppBuildInfo
import fr.descentecanyon.app.data.remote.auth.SessionManager
import fr.descentecanyon.app.data.remote.backend.BackendTestServer
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.toAppError
import fr.descentecanyon.app.domain.model.AppServerState
import fr.descentecanyon.app.domain.model.FeedbackContact
import fr.descentecanyon.app.domain.model.FeedbackDraft
import fr.descentecanyon.app.domain.model.FeedbackKind
import fr.descentecanyon.app.domain.model.FeedbackRefusal
import fr.descentecanyon.app.domain.model.FeedbackSendResult
import fr.descentecanyon.app.domain.model.FeedbackStatus
import fr.descentecanyon.app.domain.model.FeedbackTechnicalInfo
import fr.descentecanyon.app.domain.model.ServerFeatures
import fr.descentecanyon.app.domain.repository.AppServerRepository
import fr.descentecanyon.app.domain.repository.SiteAccountRepository
import fr.descentecanyon.app.testutil.TestDatabases
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The feedback fixtures are written by the Python server (server/tools/make_feedback_fixtures.py). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FeedbackRepositoryImplTest {

    private val backend = BackendTestServer()
    private val database = TestDatabases.user()
    private val serverState = MutableStateFlow(AppServerState(isConfigured = true, features = ServerFeatures(feedback = true)))
    private val appServer = mockk<AppServerRepository> { every { observeState() } returns serverState }
    private val siteAccount = mockk<SiteAccountRepository>()
    private val sessionManager = mockk<SessionManager> { every { loggedInUsername } returns null }
    private val scheduled = mutableListOf<Unit>()
    private val scheduler = object : FeedbackOutboxScheduler {
        override fun scheduleSend() {
            scheduled += Unit
        }
    }

    private fun repository() = FeedbackRepositoryImpl(
        client = backend.client,
        config = backend.config,
        installKeys = InstallKeyStore(database.keyValueDao()),
        keyValueDao = database.keyValueDao(),
        appServer = appServer,
        siteAccount = siteAccount,
        sessionManager = sessionManager,
        buildInfo = AppBuildInfo(30202, "3.2.2"),
        scheduler = scheduler,
        json = backend.json,
        clock = Clock.fixed(Instant.parse("2026-11-28T09:00:00Z"), ZoneOffset.UTC),
    )

    @After
    fun tearDown() {
        backend.shutdown()
        database.close()
    }

    /** The draft behind feedback-request.json. */
    private val draft = FeedbackDraft(
        kind = FeedbackKind.BUG,
        text = "  La carte reste blanche quand je reviens de l'onglet Sorties.\n",
        technical = FeedbackTechnicalInfo("3.2.2", 30202, "14 (API 34)", "Google Pixel 5", "fr-FR"),
        contact = FeedbackContact("Membre", "membre@example.org"),
    )

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `a message is sent as the server takes it, with the installation key, and kept in my messages`() = runTest {
        backend.server.enqueue(json(BackendTestServer.resource("backend/feedback.json"), code = 201))
        val repository = repository()

        assertEquals(FeedbackSendResult.SENT, repository.send(draft).getOrThrow())

        val request = backend.server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/feedback", request.path)
        assertEquals(43, request.getHeader("X-Install-Key")?.length)
        assertEquals(
            Json.parseToJsonElement(BackendTestServer.resource("backend/feedback-request.json")),
            Json.parseToJsonElement(request.body.readUtf8()),
        )
        val mine = repository.observeMine().first().single()
        assertEquals(FeedbackKind.BUG, mine.kind)
        assertEquals(FeedbackStatus.NEW, mine.status)
        assertFalse(mine.isQueued)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `my messages are read with the status and the reply, and a reply is new until seen`() = runTest {
        backend.server.enqueue(json(BackendTestServer.resource("backend/feedback-mine.json")))
        val repository = repository()

        val mine = repository.refreshMine().getOrThrow()

        assertEquals(listOf(FeedbackStatus.DECLINED, FeedbackStatus.PLANNED, FeedbackStatus.NEW), mine.map { it.status })
        val planned = mine[1]
        assertEquals("Bonne idée, c'est prévu pour la prochaine version.", planned.reply)
        assertTrue(planned.repliedAt != null)
        assertTrue(planned.isReplyNew)
        assertEquals("/v1/feedback/mine", backend.server.takeRequest().path)

        repository.markRepliesSeen()
        assertFalse(repository.observeMine().first()[1].isReplyNew)
    }

    @Test
    fun `without network the message is kept on the phone and leaves once the network is back`() = runTest {
        backend.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val repository = repository()

        assertEquals(FeedbackSendResult.QUEUED, repository.send(draft).getOrThrow())
        assertEquals(1, scheduled.size)
        val queued = repository.observeMine().first().single()
        assertTrue(queued.isQueued)
        assertEquals("La carte reste blanche quand je reviens de l'onglet Sorties.", queued.text)
        backend.server.takeRequest()

        backend.server.enqueue(json(BackendTestServer.resource("backend/feedback.json"), code = 201))
        repository.sendQueued().getOrThrow()

        val sent = repository.observeMine().first().single()
        assertFalse(sent.isQueued)
        assertEquals(
            Json.parseToJsonElement(BackendTestServer.resource("backend/feedback-request.json")),
            Json.parseToJsonElement(backend.server.takeRequest().body.readUtf8()),
        )
    }

    @Test
    fun `a refusal of the server is told at once, never kept for later`() = runTest {
        backend.server.enqueue(json(BackendTestServer.resource("backend/error-feedback-invalid.json"), code = 422))
        val repository = repository()

        val error = repository.send(draft).exceptionOrNull()!!.toAppError()

        assertEquals(AppError.FeedbackRefused(FeedbackRefusal.TEXT_TOO_SHORT), error)
        assertTrue(repository.observeMine().first().isEmpty())
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `a text out of bounds is refused before anything is sent`() = runTest {
        val error = repository().send(draft.copy(text = "  court ")).exceptionOrNull()!!.toAppError()

        assertEquals(AppError.FeedbackRefused(FeedbackRefusal.TEXT_TOO_SHORT), error)
        assertEquals(0, backend.server.requestCount)
    }

    @Test
    fun `nothing is sent while the server is off or has no idea box`() = runTest {
        serverState.value = AppServerState(isConfigured = true)
        assertEquals(false, repository().observeAvailable().first())
        assertEquals(AppError.DataUnavailable, repository().send(draft).exceptionOrNull()!!.toAppError())
        serverState.value = AppServerState(isConfigured = true, isEnabled = false, features = ServerFeatures(feedback = true))
        assertEquals(AppError.DataUnavailable, repository().send(draft).exceptionOrNull()!!.toAppError())
        assertEquals(0, backend.server.requestCount)
    }

    @Test
    fun `a deleted message is gone from the server and from my messages, even when the server lost it`() = runTest {
        backend.server.enqueue(json(BackendTestServer.resource("backend/feedback-mine.json")))
        backend.server.enqueue(MockResponse().setResponseCode(204))
        backend.server.enqueue(json("""{"error":"NOT_FOUND"}""", code = 404))
        val repository = repository()
        val (first, second) = repository.refreshMine().getOrThrow()
        backend.server.takeRequest()

        repository.delete(first).getOrThrow()
        val request = backend.server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/feedback/${first.id}", request.path)
        repository.delete(second).getOrThrow()

        assertEquals(1, repository.observeMine().first().size)
    }

    @Test
    fun `the contact is the account the app is logged in with, its address read from the site`() = runTest {
        assertNull(repository().contact())

        every { sessionManager.loggedInUsername } returns "Membre"
        coEvery { siteAccount.accountEmail() } returns Result.success("membre@example.org")
        assertEquals(FeedbackContact("Membre", "membre@example.org"), repository().contact())

        coEvery { siteAccount.accountEmail() } returns Result.failure(IllegalStateException())
        assertEquals(FeedbackContact("Membre", null), repository().contact())
    }

    @Test
    fun `the technical details name the app version`() {
        val info = repository().technicalInfo()

        assertEquals("3.2.2", info.appVersion)
        assertEquals(30202L, info.versionCode)
        assertTrue(info.android.contains("API"))
    }
}
