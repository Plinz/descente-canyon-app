package fr.descentecanyon.app.data.local.catalog

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.BackendConfig
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chooses, before Room opens it, which catalog the app reads: the one shipped in the APK, or a newer
 * one downloaded from the app's server.
 *
 * Room copies the APK's catalog the first time it opens it. When the app is installed or updated,
 * the previous copy is deleted so the new APK's catalog replaces it as a whole: no migration and no
 * merge of reference data is ever needed. A catalog downloaded from the server (already checked:
 * hash, Room schema, integrity) replaces the current one at the next start, the same way, when it
 * is newer; it survives an app update only if it is newer than the new APK's catalog and has its
 * exact Room schema.
 */
@Singleton
class CatalogInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val config: BackendConfig,
    private val updates: CatalogUpdateStore,
    private val hashes: CatalogHashStore,
) {

    enum class Plan {
        /** Open the installed file as it is. */
        OPEN_EXISTING,

        /** Move the downloaded catalog in place of the current one. */
        INSTALL_STAGED,

        /** Delete the current file: Room copies the APK's catalog (a few seconds). */
        COPY_FROM_APK,
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /** Identifies this installation of the APK (changes with every install or update). */
    private val bundledCatalogVersion: String by lazy {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        "${PackageInfoCompat.getLongVersionCode(packageInfo)}:${packageInfo.lastUpdateTime}"
    }

    private val databaseFile: File get() = context.getDatabasePath(CatalogDatabase.DATABASE_NAME)

    /** When the catalog of this APK was generated. */
    val embeddedVersion: Instant get() = config.embeddedCatalogVersion.toInstantOrNull() ?: Instant.EPOCH

    /** Whether opening the catalog will copy it from the APK (first launch or app update). */
    fun needsInstall(): Boolean = plan() == Plan.COPY_FROM_APK

    fun plan(): Plan {
        val exists = databaseFile.exists()
        val apkChanged = installedVersion() != bundledCatalogVersion
        val keepsServerCatalog = exists && keepsServerCatalog(apkChanged)
        val current = if (keepsServerCatalog) updates.installedServerVersion() ?: embeddedVersion else embeddedVersion
        return when {
            usableStaged(newerThan = current) != null -> Plan.INSTALL_STAGED
            !exists -> Plan.COPY_FROM_APK
            apkChanged && !keepsServerCatalog -> Plan.COPY_FROM_APK
            else -> Plan.OPEN_EXISTING
        }
    }

    /** Applies [plan]. Must run before the database is opened. */
    fun prepareBeforeOpen() {
        when (plan()) {
            Plan.INSTALL_STAGED -> installStaged()

            Plan.COPY_FROM_APK -> {
                context.deleteDatabase(CatalogDatabase.DATABASE_NAME)
                updates.setInstalledServerVersion(null)
                updates.clearStaged()
                hashes.reset()
            }

            Plan.OPEN_EXISTING -> if (updates.staged() != null) {
                updates.clearStaged()
                hashes.discardStaged()
            }
        }
        updates.deleteLeftovers()
    }

    /** Records that the catalog is installed for this APK; called once Room has opened it. */
    fun markInstalled() {
        if (installedVersion() != bundledCatalogVersion) {
            prefs.edit { putString(KEY_INSTALLED_VERSION, bundledCatalogVersion) }
        }
    }

    private fun installStaged() {
        val staged = updates.staged() ?: return
        try {
            context.deleteDatabase(CatalogDatabase.DATABASE_NAME)
            val target = databaseFile
            target.parentFile?.mkdirs()
            if (!staged.file.renameTo(target)) {
                staged.file.copyTo(target, overwrite = true)
                staged.file.delete()
            }
            updates.setInstalledServerVersion(staged.dataVersion)
            hashes.adoptStaged()
        } catch (exception: Exception) {
            // The APK's catalog is always there to fall back on.
            Log.w(TAG, "Unable to install the downloaded catalog", exception)
            context.deleteDatabase(CatalogDatabase.DATABASE_NAME)
            updates.setInstalledServerVersion(null)
            hashes.reset()
        }
        updates.clearStaged()
    }

    private fun keepsServerCatalog(apkChanged: Boolean): Boolean {
        val serverVersion = updates.installedServerVersion() ?: return false
        if (!apkChanged) return true
        return serverVersion > embeddedVersion && hasExpectedSchema(databaseFile)
    }

    private fun usableStaged(newerThan: Instant): StagedCatalog? = updates.staged()?.takeIf { staged ->
        staged.file.isFile &&
            staged.identityHash == config.catalogIdentityHash &&
            staged.dataVersion > newerThan &&
            hasExpectedSchema(staged.file)
    }

    private fun hasExpectedSchema(file: File): Boolean = config.catalogIdentityHash.isNotEmpty() &&
        readSchema(file) == config.catalogSchemaVersion to config.catalogIdentityHash

    private fun installedVersion(): String? = prefs.getString(KEY_INSTALLED_VERSION, null)

    private companion object {
        const val TAG = "CatalogInstaller"
        const val PREFS_NAME = "catalog_install"
        const val KEY_INSTALLED_VERSION = "installed_catalog_version"
    }
}

/** Room version and identity hash of a catalog file, null when it cannot be read. */
internal fun readSchema(file: File): Pair<Int, String>? = runCatching {
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
        val identity = database.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: return@use null
        database.version to identity
    }
}.getOrNull()
