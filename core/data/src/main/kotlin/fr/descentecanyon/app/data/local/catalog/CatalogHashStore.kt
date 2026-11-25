package fr.descentecanyon.app.data.local.catalog

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.BackendConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Hash of every canyon of the catalog the app reads (see scripts/app/catalog_bundles.py): the APK
 * ships those of its own catalog, and each update from the server replaces them. Comparing them with
 * the server's index tells which canyons changed.
 */
@Serializable
data class CatalogHashes(
    val schemaVersion: Int,
    val identityHash: String,
    val canyons: Map<String, String>,
    val shared: Map<String, String> = emptyMap(),
    /** SHA-256 of the server index these hashes come from; null for the APK's own catalog. */
    val indexSha256: String? = null,
)

@Singleton
class CatalogHashStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val config: BackendConfig,
    private val json: Json,
) {
    private val directory: File get() = File(context.noBackupFilesDir, DIRECTORY_NAME)
    private val current: File get() = File(directory, CURRENT_FILE)
    private val staged: File get() = File(directory, STAGED_FILE)

    /** The hashes of the catalog in use, null when unknown or of another schema (then only a whole catalog can update it). */
    fun load(): CatalogHashes? = decode(current.takeIf { it.isFile }?.readText()) ?: decode(assetText())

    fun save(hashes: CatalogHashes) {
        write(current, json.encodeToString(CatalogHashes.serializer(), hashes))
    }

    /** The catalog in use is the APK's again: its shipped hashes apply. */
    fun reset() {
        current.delete()
        staged.delete()
    }

    /** Hashes of a whole catalog downloaded and waiting for the next start. */
    fun stage(hashes: CatalogHashes) {
        write(staged, json.encodeToString(CatalogHashes.serializer(), hashes))
    }

    /** The staged catalog was installed: its hashes become the current ones (else the APK's apply). */
    fun adoptStaged() {
        if (staged.isFile) {
            staged.copyTo(current, overwrite = true)
            staged.delete()
        } else {
            current.delete()
        }
    }

    fun discardStaged() {
        staged.delete()
    }

    private fun decode(text: String?): CatalogHashes? = text?.let {
        runCatching { json.decodeFromString(CatalogHashes.serializer(), it) }.getOrNull()
    }?.takeIf { it.identityHash == config.catalogIdentityHash && it.schemaVersion == config.catalogSchemaVersion }

    private fun assetText(): String? = runCatching { context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() } }.getOrNull()

    private fun write(target: File, text: String) {
        directory.mkdirs()
        val partial = File(directory, "${target.name}.part")
        partial.writeText(text)
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "catalog-state"
        const val CURRENT_FILE = "hashes.json"
        const val STAGED_FILE = "staged-hashes.json"
        const val ASSET_NAME = "catalog_hashes.json"
    }
}
