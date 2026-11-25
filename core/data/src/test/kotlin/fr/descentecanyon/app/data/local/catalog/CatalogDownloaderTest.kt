package fr.descentecanyon.app.data.local.catalog

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import fr.descentecanyon.app.data.remote.backend.BackendTestServer
import fr.descentecanyon.app.data.remote.backend.ManifestCatalogDto
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.toAppError
import fr.descentecanyon.app.testutil.TestDatabases
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CatalogDownloaderTest {

    private val context = TestDatabases.context
    private val backend = BackendTestServer()
    private val updates = CatalogUpdateStore(context)
    private val installer = CatalogInstaller(context, backend.config, updates, CatalogHashStore(context, backend.config, backend.json))
    private val downloader = CatalogDownloader(backend.client, updates, installer, backend.config, Dispatchers.Unconfined)

    @After
    fun tearDown() {
        backend.shutdown()
        context.getSharedPreferences(CatalogUpdateStore.PREFS_NAME, 0).edit().clear().commit()
        updates.directory.deleteRecursively()
    }

    @Test
    fun `a catalog matching the manifest is checked then staged`() = runTest {
        val (packed, entry) = publishedCatalog(canyons = 4)
        backend.server.enqueue(MockResponse().setBody(Buffer().write(packed)))

        val staged = downloader.download(entry).getOrThrow()

        assertEquals("/v1/files/${entry.sha256}.db.gz", backend.server.takeRequest().path)
        assertEquals(staged, updates.staged())
        assertEquals(Instant.parse(entry.dataVersion), staged.dataVersion)
        assertTrue(staged.file.isFile)
        assertEquals(listOf(staged.file.name), updates.directory.list()?.toList())
    }

    @Test
    fun `a file that does not match the manifest is never staged`() = runTest {
        val (packed, entry) = publishedCatalog(canyons = 4)
        val cases = listOf(
            entry.copy(sha256 = "0".repeat(64)),
            entry.copy(canyonCount = 5),
            entry.copy(uncompressedSize = entry.uncompressedSize + 1),
        )
        cases.forEach { wrong ->
            backend.server.enqueue(MockResponse().setBody(Buffer().write(packed)))
            assertEquals(AppError.UnexpectedResponse, downloader.download(wrong).exceptionOrNull()?.toAppError())
        }
        val (otherSchema, otherEntry) = publishedCatalog(canyons = 4, identity = "another-schema")
        backend.server.enqueue(MockResponse().setBody(Buffer().write(otherSchema)))
        assertEquals(AppError.UnexpectedResponse, downloader.download(otherEntry).exceptionOrNull()?.toAppError())

        assertNull(updates.staged())
        assertEquals(emptyList<String>(), updates.directory.list().orEmpty().toList())
    }

    @Test
    fun `only a newer catalog of this app's schema is picked`() {
        val (_, entry) = publishedCatalog(canyons = 4)

        assertNotNull(downloader.pick(listOf(entry.copy(identityHash = "other"), entry)))
        assertNull(downloader.pick(listOf(entry.copy(identityHash = "other"))))
        assertNull(downloader.pick(listOf(entry.copy(dataVersion = "2026-10-01T00:00:00Z"))))
        updates.setInstalledServerVersion(Instant.parse(entry.dataVersion))
        assertNull(downloader.pick(listOf(entry)))
    }

    private fun publishedCatalog(canyons: Int, identity: String = BackendTestServer.IDENTITY): Pair<ByteArray, ManifestCatalogDto> {
        val file = File(context.cacheDir, "published-$identity-$canyons.db").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            database.execSQL("INSERT INTO room_master_table VALUES (42, '$identity')")
            database.execSQL("CREATE TABLE canyons (id INTEGER PRIMARY KEY, nom TEXT NOT NULL)")
            repeat(canyons) { database.execSQL("INSERT INTO canyons VALUES (${it + 1}, 'Canyon')") }
            database.version = 4
        }
        val raw = file.readBytes()
        val packed = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(raw) } }.toByteArray()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(packed).joinToString("") { "%02x".format(it) }
        val entry = ManifestCatalogDto(
            schemaVersion = 4,
            identityHash = identity,
            dataVersion = "2026-11-25T02:41:07Z",
            path = "files/$sha256.db.gz",
            sha256 = sha256,
            size = packed.size.toLong(),
            uncompressedSize = raw.size.toLong(),
            canyonCount = canyons,
        )
        return packed to entry
    }
}
