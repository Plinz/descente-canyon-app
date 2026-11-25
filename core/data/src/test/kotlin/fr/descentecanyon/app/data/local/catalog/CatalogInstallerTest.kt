package fr.descentecanyon.app.data.local.catalog

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import fr.descentecanyon.app.data.BackendConfig
import fr.descentecanyon.app.testutil.TestDatabases
import java.io.File
import java.time.Instant
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
class CatalogInstallerTest {

    private val context = TestDatabases.context
    private val catalogFile = context.getDatabasePath(CatalogDatabase.DATABASE_NAME)
    private val config = BackendConfig(
        baseUrl = "https://api.example.org",
        publicKeys = listOf("key"),
        catalogSchemaVersion = 4,
        catalogIdentityHash = IDENTITY,
        embeddedCatalogVersion = EMBEDDED.toString(),
    )
    private val updates = CatalogUpdateStore(context)
    private val hashes = CatalogHashStore(context, config, kotlinx.serialization.json.Json { ignoreUnknownKeys = true })

    @After
    fun tearDown() {
        context.deleteDatabase(CatalogDatabase.DATABASE_NAME)
        context.getSharedPreferences("catalog_install", 0).edit().clear().commit()
        context.getSharedPreferences(CatalogUpdateStore.PREFS_NAME, 0).edit().clear().commit()
        updates.directory.deleteRecursively()
    }

    @Test
    fun `a catalog installed by another APK is discarded before opening`() {
        writeCatalog(catalogFile)
        val installer = CatalogInstaller(context, config, updates, hashes)

        assertTrue(installer.needsInstall())
        installer.prepareBeforeOpen()

        assertFalse(catalogFile.exists())
    }

    @Test
    fun `the catalog of this APK is kept once marked installed`() {
        writeCatalog(catalogFile)
        val installer = CatalogInstaller(context, config, updates, hashes)
        installer.markInstalled()

        installer.prepareBeforeOpen()

        assertTrue(catalogFile.exists())
        assertFalse(installer.needsInstall())
    }

    @Test
    fun `a missing catalog file needs an install even when marked installed`() {
        val installer = CatalogInstaller(context, config, updates, hashes)
        installer.markInstalled()

        assertTrue(installer.needsInstall())
    }

    @Test
    fun `a newer downloaded catalog replaces the current one at the next start`() {
        val installer = installedApkCatalog()
        val version = Instant.parse("2026-11-25T02:41:07Z")
        stage(version, canyons = 5)

        assertEquals(CatalogInstaller.Plan.INSTALL_STAGED, installer.plan())
        assertFalse(installer.needsInstall())
        installer.prepareBeforeOpen()

        assertEquals(5, canyonCount(catalogFile))
        assertEquals(version, updates.installedServerVersion())
        assertNull(updates.staged())
        assertEquals(emptyList<String>(), updates.directory.list().orEmpty().toList())
        assertEquals(CatalogInstaller.Plan.OPEN_EXISTING, installer.plan())
    }

    @Test
    fun `a downloaded catalog older than the current one or of another schema is dropped`() {
        val installer = installedApkCatalog()
        stage(EMBEDDED.minusSeconds(3600))
        installer.prepareBeforeOpen()
        assertEquals(3, canyonCount(catalogFile))
        assertNull(updates.staged())

        stage(Instant.parse("2026-11-25T02:41:07Z"), identity = "another-schema")
        assertEquals(CatalogInstaller.Plan.OPEN_EXISTING, installer.plan())
        installer.prepareBeforeOpen()
        assertEquals(3, canyonCount(catalogFile))
        assertNull(updates.installedServerVersion())
    }

    @Test
    fun `a newer server catalog survives an app update when it has the new app's schema`() {
        writeCatalog(catalogFile, canyons = 7)
        updates.setInstalledServerVersion(EMBEDDED.plusSeconds(86_400))
        simulateAppUpdate()
        val installer = CatalogInstaller(context, config, updates, hashes)

        assertEquals(CatalogInstaller.Plan.OPEN_EXISTING, installer.plan())
        installer.prepareBeforeOpen()

        assertEquals(7, canyonCount(catalogFile))
        assertEquals(EMBEDDED.plusSeconds(86_400), updates.installedServerVersion())
    }

    @Test
    fun `an app update drops a server catalog of another schema or older than the new APK's`() {
        writeCatalog(catalogFile, identity = "old-schema")
        updates.setInstalledServerVersion(EMBEDDED.plusSeconds(86_400))
        simulateAppUpdate()
        CatalogInstaller(context, config, updates, hashes).prepareBeforeOpen()
        assertFalse(catalogFile.exists())
        assertNull(updates.installedServerVersion())

        writeCatalog(catalogFile)
        updates.setInstalledServerVersion(EMBEDDED.minusSeconds(86_400))
        simulateAppUpdate()
        CatalogInstaller(context, config, updates, hashes).prepareBeforeOpen()
        assertFalse(catalogFile.exists())
        assertNull(updates.installedServerVersion())
    }

    @Test
    fun `without a server built in, the catalog follows the APK only`() {
        val installer = CatalogInstaller(context, BackendConfig.NONE, updates, hashes)
        writeCatalog(catalogFile)
        installer.markInstalled()
        stage(Instant.parse("2026-11-25T02:41:07Z"))

        assertEquals(CatalogInstaller.Plan.OPEN_EXISTING, installer.plan())
    }

    private fun installedApkCatalog(): CatalogInstaller {
        writeCatalog(catalogFile, canyons = 3)
        return CatalogInstaller(context, config, updates, hashes).also { it.markInstalled() }
    }

    private fun simulateAppUpdate() {
        context.getSharedPreferences("catalog_install", 0).edit().putString("installed_catalog_version", "1:previous-apk").commit()
    }

    private fun stage(version: Instant, identity: String = IDENTITY, canyons: Int = 5) {
        val file = File(updates.directory.apply { mkdirs() }, "${version.epochSecond}.db")
        writeCatalog(file, identity = identity, canyons = canyons)
        updates.stage(StagedCatalog(file, version, identity, sha256 = "sha-${version.epochSecond}"))
    }

    private fun writeCatalog(file: File, identity: String = IDENTITY, canyons: Int = 3) {
        file.parentFile?.mkdirs()
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            database.execSQL("INSERT INTO room_master_table VALUES (42, '$identity')")
            database.execSQL("CREATE TABLE canyons (id INTEGER PRIMARY KEY, nom TEXT NOT NULL)")
            repeat(canyons) { database.execSQL("INSERT INTO canyons VALUES (${it + 1}, 'Canyon ${it + 1}')") }
            database.version = 4
        }
    }

    private fun canyonCount(file: File): Int = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
        database.rawQuery("SELECT COUNT(*) FROM canyons", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    private companion object {
        const val IDENTITY = "38ceb224a4367c7742d73e390e93467c"
        val EMBEDDED: Instant = Instant.parse("2026-10-27T17:26:31.659642Z")
    }
}
