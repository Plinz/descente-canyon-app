package fr.descentecanyon.app.data.repository

import android.app.Application
import fr.descentecanyon.app.data.AppBuildInfo
import fr.descentecanyon.app.data.local.catalog.CatalogBundleApplier
import fr.descentecanyon.app.data.local.catalog.CatalogDownloader
import fr.descentecanyon.app.data.local.catalog.CatalogHashStore
import fr.descentecanyon.app.data.local.catalog.CatalogInstaller
import fr.descentecanyon.app.data.local.catalog.CatalogSync
import fr.descentecanyon.app.data.local.catalog.CatalogUpdateStore
import fr.descentecanyon.app.data.remote.backend.BackendTestServer
import fr.descentecanyon.app.domain.model.CatalogSource
import fr.descentecanyon.app.domain.model.ServerMessageLevel
import fr.descentecanyon.app.testutil.TestDatabases
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppServerRepositoryImplTest {

    private val context = TestDatabases.context
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val fixtureKey = BackendTestServer.resource("backend/manifest-public-key.txt").trim()
    private val backend = BackendTestServer(publicKeys = listOf(fixtureKey, Base64.getEncoder().encodeToString(keyPair.public.encoded)))
    private val database = TestDatabases.user()
    private val updates = CatalogUpdateStore(context)
    private val hashStore = CatalogHashStore(context, backend.config, backend.json)
    private val installer = CatalogInstaller(context, backend.config, updates, hashStore)
    private val catalogDatabase = TestDatabases.catalog()
    private val scheduler = RecordingScheduler()
    private val clock = Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC)

    private fun repository(versionCode: Int = 59) = AppServerRepositoryImpl(
        config = backend.config,
        buildInfo = AppBuildInfo(versionCode, "test"),
        client = backend.client,
        keyValueDao = database.keyValueDao(),
        json = backend.json,
        clock = clock,
        catalogInstaller = installer,
        catalogUpdates = updates,
        catalogDownloader = CatalogDownloader(backend.client, updates, installer, backend.config, Dispatchers.Unconfined),
        catalogSync = CatalogSync(
            backend.client,
            backend.config,
            hashStore,
            CatalogBundleApplier(catalogDatabase),
            updates,
            backend.json,
            Dispatchers.Unconfined,
        ),
        scheduler = scheduler,
        predictionStore = predictionStore,
    )

    private val predictionStore = ServerPredictionStore(java.io.File(context.cacheDir, "server-predictions-test"), backend.json, clock)

    @After
    fun tearDown() {
        predictionStore.clear()
        backend.shutdown()
        database.close()
        catalogDatabase.close()
        context.getSharedPreferences(CatalogUpdateStore.PREFS_NAME, 0).edit().clear().commit()
    }

    @Test
    fun `the manifest of the server gives its messages, switches and a newer catalog to download`() = runTest {
        backend.server.enqueue(MockResponse().setBody(BackendTestServer.resource("backend/manifest-envelope.json")))
        val repository = repository(versionCode = 49)

        repository.refresh().getOrThrow()
        val state = repository.observeState().first()

        assertEquals("/v1/manifest.json", backend.server.takeRequest().path)
        assertTrue(state.isActive)
        assertTrue(state.updateRequired)
        assertFalse(state.features.communityTracks)
        assertFalse(state.communityTracksAvailable)
        assertEquals(ServerMessageLevel.WARNING, state.messages.single().level)
        assertEquals("Le site est en maintenance ce soir.", state.messages.single().textFor("fr"))
        assertEquals("The site is under maintenance tonight.", state.messages.single().textFor("de"))
        assertEquals(clock.instant(), state.lastCheckedAt)
        assertFalse(state.lastCheckFailed)
        assertEquals(CatalogSource.APP, state.catalog.source)
        assertEquals(listOf(false), scheduler.scheduled)
    }

    @Test
    fun `a hidden message stays hidden, a version within the range sees no update notice`() = runTest {
        backend.server.enqueue(MockResponse().setBody(BackendTestServer.resource("backend/manifest-envelope.json")))
        val repository = repository(versionCode = 59)
        repository.refresh().getOrThrow()

        repository.dismissMessage("site-2026-12")
        val state = repository.observeState().first()

        assertTrue(state.messages.isEmpty())
        assertFalse(state.updateRequired)
    }

    @Test
    fun `a manifest failing its signature or replayed older changes nothing but the check status`() = runTest {
        val repository = repository()
        val newer = """{"schema":1,"sequence":5,"messages":[{"id":"new","level":"info","text":{"fr":"Nouveau"}}]}"""
        val older = """{"schema":1,"sequence":4,"messages":[{"id":"old","level":"info","text":{"fr":"Ancien"}}]}"""
        backend.server.enqueue(MockResponse().setBody(signed(newer)))
        repository.refresh().getOrThrow()

        backend.server.enqueue(MockResponse().setBody(signed(older)))
        repository.refresh().getOrThrow()
        assertEquals(listOf("new"), repository.observeState().first().messages.map { it.id })

        val forged = signed("""{"schema":1,"sequence":9,"messages":[]}""").replace("\"signature\":\"", "\"signature\":\"AA")
        backend.server.enqueue(MockResponse().setBody(forged))
        assertTrue(repository.refresh().isFailure)
        val state = repository.observeState().first()
        assertEquals(listOf("new"), state.messages.map { it.id })
        assertTrue(state.lastCheckFailed)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `switched off, the server is never called and pending downloads are cancelled`() = runTest {
        val repository = repository()

        repository.setEnabled(false)
        repository.refresh().getOrThrow()

        assertEquals(0, backend.server.requestCount)
        assertEquals(1, scheduler.cancelled)
        assertFalse(repository.observeState().first().isActive)
    }

    @Test
    fun `mobile data for catalogs is remembered and used for the download`() = runTest {
        backend.server.enqueue(MockResponse().setBody(BackendTestServer.resource("backend/manifest-envelope.json")))
        val repository = repository()

        repository.setCatalogOnMobileData(true)
        repository.refresh().getOrThrow()

        assertTrue(repository.observeState().first().catalogOnMobileData)
        assertEquals(listOf(true), scheduler.scheduled)
    }

    @Test
    fun `the estimates the manifest announces are downloaded once, checked and kept`() = runTest {
        val bytes = javaClass.classLoader!!.getResourceAsStream("backend/predictions.json.gz")!!.use { it.readBytes() }
        val sha = AppServerRepositoryImpl.sha256Hex(bytes)
        val manifest = signed(
            """{"schema":1,"sequence":7,"predictions":{"path":"files/$sha.json.gz","sha256":"$sha","size":${bytes.size},"schemaVersion":1}}""",
        )
        backend.server.enqueue(MockResponse().setBody(manifest))
        backend.server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        val repository = repository()

        repository.refresh().getOrThrow()

        assertEquals("/v1/manifest.json", backend.server.takeRequest().path)
        assertEquals("/v1/files/$sha.json.gz", backend.server.takeRequest().path)
        assertEquals(sha, predictionStore.installedSha256())
        assertTrue(predictionStore.predictions(23222)?.computedOnServer == true)

        // Checked again within the delay: nothing is read; after it, the same file is not downloaded again.
        repository.refreshIfOlderThan(java.time.Duration.ofMinutes(90)).getOrThrow()
        assertEquals(2, backend.server.requestCount)
        backend.server.enqueue(MockResponse().setBody(manifest))
        repository.refresh().getOrThrow()
        assertEquals(3, backend.server.requestCount)
    }

    @Test
    fun `an estimates file that does not match the manifest is not kept`() = runTest {
        val bytes = javaClass.classLoader!!.getResourceAsStream("backend/predictions.json.gz")!!.use { it.readBytes() }
        val manifest = signed(
            """{"schema":1,"sequence":7,"predictions":{"path":"files/x.json.gz","sha256":"${"0".repeat(
                64,
            )}","size":${bytes.size},"schemaVersion":1}}""",
        )
        backend.server.enqueue(MockResponse().setBody(manifest))
        backend.server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))

        repository().refresh().getOrThrow()

        assertEquals(null, predictionStore.installedSha256())
    }

    private fun signed(payload: String): String {
        val bytes = payload.toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(bytes)
            sign()
        }
        val encoder = Base64.getEncoder()
        return """{"format":1,"payload":"${encoder.encodeToString(bytes)}","signature":"${encoder.encodeToString(signature)}"}"""
    }

    private class RecordingScheduler : CatalogUpdateScheduler {
        val scheduled = mutableListOf<Boolean>()
        var cancelled = 0

        override fun scheduleDownload(allowMobileData: Boolean) {
            scheduled += allowMobileData
        }

        override fun cancelDownload() {
            cancelled++
        }
    }
}
