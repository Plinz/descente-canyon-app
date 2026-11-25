package fr.descentecanyon.app.data.local.catalog

import android.database.sqlite.SQLiteDatabase
import fr.descentecanyon.app.data.BackendConfig
import fr.descentecanyon.app.data.remote.backend.BackendClient
import fr.descentecanyon.app.data.remote.backend.ManifestCatalogDto
import fr.descentecanyon.app.domain.coroutines.IoDispatcher
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import fr.descentecanyon.app.domain.error.suspendRunCatching
import java.io.File
import java.time.Instant
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Downloads a catalog announced by the signed manifest and checks it before staging it: SHA-256 of
 * the file, exact size, Room version and identity hash of this app's catalog schema, SQLite
 * integrity, number of canyons. [CatalogInstaller] puts it in place at the next start.
 */
@Singleton
class CatalogDownloader @Inject constructor(
    private val client: BackendClient,
    private val updates: CatalogUpdateStore,
    private val installer: CatalogInstaller,
    private val config: BackendConfig,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** The entry this app can read, when it is newer than the catalog it reads or has already staged. */
    fun pick(entries: List<ManifestCatalogDto>): ManifestCatalogDto? {
        if (!config.isConfigured) return null
        val entry = entries.firstOrNull {
            it.schemaVersion == config.catalogSchemaVersion && it.identityHash == config.catalogIdentityHash
        } ?: return null
        val version = entry.dataVersion.toInstantOrNull() ?: return null
        val current = listOfNotNull(updates.installedServerVersion(), installer.embeddedVersion, updates.staged()?.dataVersion)
            .maxOrNull() ?: Instant.EPOCH
        return entry.takeIf { version > current }
    }

    suspend fun download(entry: ManifestCatalogDto): Result<StagedCatalog> = suspendRunCatching {
        val version = entry.dataVersion.toInstantOrNull() ?: appError(AppError.UnexpectedResponse)
        val directory = updates.directory.apply { mkdirs() }
        val packed = File(directory, "${entry.sha256}.db.gz.part")
        val unpacked = File(directory, "${entry.sha256}.db.part")
        try {
            val sha256 = client.download(entry.path, packed, maxBytes = entry.size)
            if (!sha256.equals(entry.sha256, ignoreCase = true) || packed.length() != entry.size) {
                appError(AppError.UnexpectedResponse)
            }
            withContext(ioDispatcher) {
                unpack(packed, unpacked, entry.uncompressedSize)
                check(unpacked, entry)
            }
            val target = File(directory, "${entry.sha256}.db")
            if (!unpacked.renameTo(target)) appError(AppError.StorageFailure)
            val staged = StagedCatalog(target, version, entry.identityHash, entry.sha256)
            updates.stage(staged)
            updates.deleteLeftovers()
            staged
        } finally {
            packed.delete()
            unpacked.delete()
        }
    }

    private fun unpack(packed: File, target: File, expectedSize: Long) {
        var written = 0L
        GZIPInputStream(packed.inputStream().buffered()).use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    written += read
                    if (written > expectedSize) appError(AppError.UnexpectedResponse)
                    output.write(buffer, 0, read)
                }
            }
        }
        if (written != expectedSize) appError(AppError.UnexpectedResponse)
    }

    private fun check(file: File, entry: ManifestCatalogDto) {
        if (readSchema(file) != config.catalogSchemaVersion to config.catalogIdentityHash) appError(AppError.UnexpectedResponse)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            val integrity = database.rawQuery("PRAGMA quick_check", null).use { if (it.moveToFirst()) it.getString(0) else null }
            if (integrity != "ok") appError(AppError.UnexpectedResponse)
            val canyons = database.rawQuery("SELECT COUNT(*) FROM canyons", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            if (canyons == 0 || canyons != entry.canyonCount) appError(AppError.UnexpectedResponse)
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
