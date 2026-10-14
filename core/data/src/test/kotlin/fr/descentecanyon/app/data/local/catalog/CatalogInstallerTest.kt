package fr.descentecanyon.app.data.local.catalog

import android.app.Application
import fr.descentecanyon.app.testutil.TestDatabases
import org.junit.After
import org.junit.Assert.assertFalse
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

    @After
    fun tearDown() {
        context.deleteDatabase(CatalogDatabase.DATABASE_NAME)
        context.getSharedPreferences("catalog_install", 0).edit().clear().commit()
    }

    @Test
    fun `a catalog installed by another APK is discarded before opening`() {
        writeCatalogFile()
        val installer = CatalogInstaller(context)

        assertTrue(installer.needsInstall())
        installer.discardOutdatedCatalog()

        assertFalse(catalogFile.exists())
    }

    @Test
    fun `the catalog of this APK is kept once marked installed`() {
        writeCatalogFile()
        val installer = CatalogInstaller(context)
        installer.markInstalled()

        installer.discardOutdatedCatalog()

        assertTrue(catalogFile.exists())
        assertFalse(installer.needsInstall())
    }

    @Test
    fun `a missing catalog file needs an install even when marked installed`() {
        val installer = CatalogInstaller(context)
        installer.markInstalled()

        assertTrue(installer.needsInstall())
    }

    private fun writeCatalogFile() {
        catalogFile.parentFile?.mkdirs()
        catalogFile.writeText("catalog")
    }
}
