package fr.descentecanyon.app.data.local.catalog

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A downloaded catalog, checked and waiting for the next start to replace the current one. */
data class StagedCatalog(val file: File, val dataVersion: Instant, val identityHash: String, val sha256: String)

data class CatalogUpdateSnapshot(val installedServerVersion: Instant?, val staged: StagedCatalog?)

/**
 * State of the catalogs downloaded from the server. Kept in its own preferences file, readable
 * synchronously before Room opens the catalog and left out of Android backups (the catalog itself is
 * not backed up either), with the files in `noBackupFilesDir`.
 */
@Singleton
class CatalogUpdateStore @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    val directory: File get() = File(context.noBackupFilesDir, DIRECTORY_NAME)

    private val snapshotFlow by lazy { MutableStateFlow(read()) }

    val snapshot: StateFlow<CatalogUpdateSnapshot> get() = snapshotFlow.asStateFlow()

    fun installedServerVersion(): Instant? = prefs.getString(KEY_INSTALLED_SERVER_VERSION, null)?.toInstantOrNull()

    fun staged(): StagedCatalog? {
        val name = prefs.getString(KEY_STAGED_FILE, null) ?: return null
        val version = prefs.getString(KEY_STAGED_VERSION, null)?.toInstantOrNull() ?: return null
        val identity = prefs.getString(KEY_STAGED_IDENTITY, null) ?: return null
        val sha256 = prefs.getString(KEY_STAGED_SHA256, null) ?: return null
        return StagedCatalog(File(directory, name), version, identity, sha256)
    }

    fun stage(staged: StagedCatalog) {
        prefs.edit(commit = true) {
            putString(KEY_STAGED_FILE, staged.file.name)
            putString(KEY_STAGED_VERSION, staged.dataVersion.toString())
            putString(KEY_STAGED_IDENTITY, staged.identityHash)
            putString(KEY_STAGED_SHA256, staged.sha256)
        }
        publish()
    }

    fun clearStaged() {
        prefs.edit(commit = true) {
            remove(KEY_STAGED_FILE)
            remove(KEY_STAGED_VERSION)
            remove(KEY_STAGED_IDENTITY)
            remove(KEY_STAGED_SHA256)
        }
        publish()
    }

    fun setInstalledServerVersion(version: Instant?) {
        prefs.edit(commit = true) {
            if (version == null) remove(KEY_INSTALLED_SERVER_VERSION) else putString(KEY_INSTALLED_SERVER_VERSION, version.toString())
        }
        publish()
    }

    /**
     * Deletes every file of the folder but the staged catalog: replaced catalogs and interrupted
     * downloads. A partial file written in the last hour is left alone: the download worker can be
     * running while the app starts.
     */
    fun deleteLeftovers(now: Long = System.currentTimeMillis()) {
        val keep = staged()?.file?.name
        directory.listFiles()?.forEach { file ->
            val inProgress = file.name.endsWith(PARTIAL_SUFFIX) && now - file.lastModified() < PARTIAL_GRACE_MS
            if (file.name != keep && !inProgress) file.delete()
        }
    }

    private fun read() = CatalogUpdateSnapshot(installedServerVersion(), staged())

    private fun publish() {
        snapshotFlow.value = read()
    }

    companion object {
        const val PREFS_NAME = "catalog_updates"
        private const val DIRECTORY_NAME = "catalog-update"
        private const val KEY_INSTALLED_SERVER_VERSION = "installed_server_version"
        private const val KEY_STAGED_FILE = "staged_file"
        private const val KEY_STAGED_VERSION = "staged_version"
        private const val KEY_STAGED_IDENTITY = "staged_identity"
        private const val KEY_STAGED_SHA256 = "staged_sha256"
        private const val PARTIAL_SUFFIX = ".part"
        private const val PARTIAL_GRACE_MS = 60L * 60 * 1000
    }
}

/** ISO-8601 instant, with "Z" or an explicit offset (older java.time only parses "Z" in Instant.parse). */
internal fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
    ?: runCatching { OffsetDateTime.parse(this).toInstant() }.getOrNull()
