package fr.descentecanyon.app.data.local.catalog

import android.app.Application
import fr.descentecanyon.app.data.remote.backend.BackendTestServer
import fr.descentecanyon.app.data.remote.backend.ManifestFileDto
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.toAppError
import fr.descentecanyon.app.testutil.TestDatabases
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CatalogSyncTest {

    private val context = TestDatabases.context
    private val backend = BackendTestServer()
    private val database = TestDatabases.catalog()
    private val updates = CatalogUpdateStore(context)
    private val hashStore = CatalogHashStore(context, backend.config, backend.json)
    private val sync =
        CatalogSync(
            backend.client,
            backend.config,
            hashStore,
            CatalogBundleApplier(database),
            updates,
            backend.json,
            Dispatchers.Unconfined,
        )
    private val bundle = BackendTestServer.resource("backend/catalog-bundle-21.json").toByteArray()
    private val bundleHash = BackendTestServer.resource("backend/catalog-bundle-21.hash").trim()

    @After
    fun tearDown() {
        backend.shutdown()
        database.close()
        hashStore.reset()
        context.getSharedPreferences(CatalogUpdateStore.PREFS_NAME, 0).edit().clear().commit()
    }

    @Test
    fun `only the changed canyons are downloaded and applied at once`() = runTest {
        hashStore.save(CatalogHashes(4, BackendTestServer.IDENTITY, canyons = mapOf("21" to "older-version")))
        val (index, entry) = index(mapOf("21" to bundleHash))
        backend.server.enqueue(MockResponse().setBody(Buffer().write(index)))
        backend.server.enqueue(MockResponse().setBody(Buffer().write(gzip(bundle))))

        val outcome = sync.sync(entry).getOrThrow()

        assertEquals(CatalogSync.Outcome.Applied(changedCanyons = 1, removedCanyons = 0), outcome)
        assertEquals("/v1/${entry.path}", backend.server.takeRequest().path)
        assertEquals("/v1/catalog/bundles/$bundleHash.json.gz", backend.server.takeRequest().path)
        assertTrue(database.canyonDao().getById(21)!!.isForbidden)
        assertEquals(1_795_572_000_000L, database.canyonDao().getById(21)!!.lastUpdated)
        val saved = hashStore.load()!!
        assertEquals(bundleHash, saved.canyons["21"])
        assertEquals(entry.sha256, saved.indexSha256)
        assertEquals(Instant.parse(DATA_VERSION), updates.installedServerVersion())

        assertEquals(CatalogSync.Outcome.UpToDate, sync.sync(entry).getOrThrow())
        assertEquals(2, backend.server.requestCount)
    }

    @Test
    fun `a bundle that is not what the index announced is refused and nothing changes`() = runTest {
        hashStore.save(CatalogHashes(4, BackendTestServer.IDENTITY, canyons = mapOf("21" to "older-version")))
        val (index, entry) = index(mapOf("21" to bundleHash))
        backend.server.enqueue(MockResponse().setBody(Buffer().write(index)))
        val tampered = bundle.decodeToString().replace("Canyon interdit par arrêté.", "Tout va bien.").toByteArray()
        backend.server.enqueue(MockResponse().setBody(Buffer().write(gzip(tampered))))

        val error = sync.sync(entry).exceptionOrNull()?.toAppError()

        assertEquals(AppError.UnexpectedResponse, error)
        assertNull(database.canyonDao().getById(21))
        assertEquals("older-version", hashStore.load()!!.canyons["21"])
        assertNull(updates.installedServerVersion())
    }

    @Test
    fun `without hashes for its catalog or far behind, the app needs the whole catalog`() = runTest {
        val (_, entry) = index(mapOf("21" to bundleHash))
        assertEquals(CatalogSync.Outcome.NeedsWholeCatalog, sync.sync(entry).getOrThrow())
        assertEquals(0, backend.server.requestCount)

        hashStore.save(CatalogHashes(4, BackendTestServer.IDENTITY, canyons = (1..500).associate { "$it" to "old" }))
        val (bigIndex, bigEntry) = index((1..500).associate { "$it" to "new" })
        backend.server.enqueue(MockResponse().setBody(Buffer().write(bigIndex)))
        assertEquals(CatalogSync.Outcome.NeedsWholeCatalog, sync.sync(bigEntry).getOrThrow())
        assertFalse(hashStore.load()!!.canyons["1"] == "new")
    }

    @Test
    fun `an index of another schema is ignored`() = runTest {
        hashStore.save(CatalogHashes(4, BackendTestServer.IDENTITY, canyons = mapOf("21" to "older-version")))
        val (_, entry) = index(mapOf("21" to bundleHash))

        assertEquals(CatalogSync.Outcome.NotAvailable, sync.sync(entry.copy(identityHash = "another")).getOrThrow())
        assertEquals(0, backend.server.requestCount)
    }

    private fun index(canyons: Map<String, String>): Pair<ByteArray, ManifestFileDto> {
        val entries = canyons.entries.joinToString(",") { (id, hash) -> "\"$id\":[\"$hash\",1795572000000]" }
        val json = """{"bundleSchema":1,"schemaVersion":4,"identityHash":"${BackendTestServer.IDENTITY}","dataVersion":"$DATA_VERSION",""" +
            """"bundlePath":"catalog/bundles/{hash}.json.gz","canyons":{$entries},"shared":{}}"""
        val packed = gzip(json.toByteArray())
        val sha = MessageDigest.getInstance("SHA-256").digest(packed).joinToString("") { "%02x".format(it) }
        return packed to ManifestFileDto(
            path = "files/$sha.json.gz",
            sha256 = sha,
            size = packed.size.toLong(),
            dataVersion = DATA_VERSION,
            schemaVersion = 4,
            identityHash = BackendTestServer.IDENTITY,
        )
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
    }.toByteArray()

    private companion object {
        const val DATA_VERSION = "2026-11-26T02:30:00Z"
    }
}
