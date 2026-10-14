package fr.descentecanyon.app.data.local.catalog

import android.content.Context
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the installed catalog identical to the one shipped in the APK.
 *
 * Room copies the catalog from the assets the first time it opens it. Whenever the app is installed
 * or updated, the previous copy is deleted before the database is opened, so the new APK's catalog
 * replaces it as a whole: no migration and no merge of reference data is ever needed.
 */
@Singleton
class CatalogInstaller @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /** Identifies this installation of the APK (changes with every install or update). */
    private val bundledCatalogVersion: String by lazy {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        "${PackageInfoCompat.getLongVersionCode(packageInfo)}:${packageInfo.lastUpdateTime}"
    }

    /** Whether opening the catalog will copy it from the APK (first launch or app update). */
    fun needsInstall(): Boolean = installedVersion() != bundledCatalogVersion ||
        !context.getDatabasePath(CatalogDatabase.DATABASE_NAME).exists()

    /** Deletes a catalog installed by another APK. Must run before the database is opened. */
    fun discardOutdatedCatalog() {
        if (installedVersion() != bundledCatalogVersion) {
            context.deleteDatabase(CatalogDatabase.DATABASE_NAME)
        }
    }

    /** Records that the catalog of this APK is installed; called once Room has opened it. */
    fun markInstalled() {
        if (installedVersion() != bundledCatalogVersion) {
            prefs.edit { putString(KEY_INSTALLED_VERSION, bundledCatalogVersion) }
        }
    }

    private fun installedVersion(): String? = prefs.getString(KEY_INSTALLED_VERSION, null)

    private companion object {
        const val PREFS_NAME = "catalog_install"
        const val KEY_INSTALLED_VERSION = "installed_catalog_version"
    }
}
