package fr.descentecanyon.app.data.local.catalog

import fr.descentecanyon.app.data.BackendConfig
import fr.descentecanyon.app.data.remote.backend.BackendClient
import fr.descentecanyon.app.data.remote.backend.CatalogIndexDto
import fr.descentecanyon.app.data.remote.backend.ManifestFileDto
import fr.descentecanyon.app.domain.coroutines.IoDispatcher
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import fr.descentecanyon.app.domain.error.suspendRunCatching
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Brings the catalog in use up to the server's, canyon by canyon: reads the signed index, downloads
 * the bundles of the canyons whose hash differs and applies them in the open database. Far behind
 * (or without the hashes of its catalog), the app downloads the whole catalog instead.
 */
@Singleton
class CatalogSync @Inject constructor(
    private val client: BackendClient,
    private val config: BackendConfig,
    private val hashStore: CatalogHashStore,
    private val applier: CatalogBundleApplier,
    private val updates: CatalogUpdateStore,
    private val json: Json,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    sealed interface Outcome {
        data object UpToDate : Outcome

        data class Applied(val changedCanyons: Int, val removedCanyons: Int) : Outcome

        /** Too many changes, or no hashes for the catalog in use: download the whole catalog. */
        data object NeedsWholeCatalog : Outcome

        /** No index of this app's catalog schema on the server. */
        data object NotAvailable : Outcome
    }

    suspend fun sync(entry: ManifestFileDto): Result<Outcome> = suspendRunCatching {
        if (entry.identityHash != config.catalogIdentityHash || entry.schemaVersion != config.catalogSchemaVersion) {
            return@suspendRunCatching Outcome.NotAvailable
        }
        val local = hashStore.load() ?: return@suspendRunCatching Outcome.NeedsWholeCatalog
        if (local.indexSha256 == entry.sha256) return@suspendRunCatching Outcome.UpToDate

        val index = json.decodeFromString(CatalogIndexDto.serializer(), verifiedText(entry.path, entry.sha256, entry.size))
        if (index.bundleSchema != BUNDLE_SCHEMA || index.identityHash != config.catalogIdentityHash) {
            return@suspendRunCatching Outcome.NotAvailable
        }
        val remote = index.canyons.mapValues { (_, value) -> value.first().content }
        val changed = remote.filter { (id, hash) -> local.canyons[id] != hash }
        val removed = local.canyons.keys - remote.keys
        if (changed.size + removed.size > MAX_BUNDLES) return@suspendRunCatching Outcome.NeedsWholeCatalog

        val bundles = coroutineScope {
            val permits = Semaphore(PARALLEL_DOWNLOADS)
            changed.map { (_, hash) ->
                async { permits.withPermit { bundle(index.bundlePath.replace("{hash}", hash), hash) } }
            }.awaitAll()
        }
        val sharedTables = index.shared
            .filter { (table, entry) -> local.shared[table] != entry.hash }
            .mapValues { (_, entry) -> bundle(entry.path, entry.hash).getValue("rows").jsonArray }
        val readAt = index.canyons.mapNotNull { (id, value) -> value.getOrNull(1)?.longOrNull?.let { id.toInt() to it } }.toMap()

        applier.apply(CatalogChanges(bundles, removed.map(String::toInt), readAt, sharedTables))
        hashStore.save(
            CatalogHashes(
                schemaVersion = index.schemaVersion,
                identityHash = index.identityHash,
                canyons = remote,
                shared = index.shared.mapValues { it.value.hash },
                indexSha256 = entry.sha256,
            ),
        )
        index.dataVersion.toInstantOrNull()?.let(updates::setInstalledServerVersion)
        Outcome.Applied(changedCanyons = changed.size, removedCanyons = removed.size)
    }

    /** The index of the catalog, to stage with a whole catalog downloaded at the same time. */
    suspend fun stageHashesFor(entry: ManifestFileDto) {
        if (entry.identityHash != config.catalogIdentityHash) return
        val index = json.decodeFromString(CatalogIndexDto.serializer(), verifiedText(entry.path, entry.sha256, entry.size))
        hashStore.stage(
            CatalogHashes(
                schemaVersion = index.schemaVersion,
                identityHash = index.identityHash,
                canyons = index.canyons.mapValues { (_, value) -> value.first().content },
                shared = index.shared.mapValues { it.value.hash },
                indexSha256 = entry.sha256,
            ),
        )
    }

    private suspend fun verifiedText(path: String, sha256: String, size: Long): String {
        val packed = client.fetchFile(path, maxBytes = size)
        return withContext(ioDispatcher) {
            if (packed.size.toLong() != size || sha256Hex(packed) != sha256.lowercase()) appError(AppError.UnexpectedResponse)
            gunzip(packed).decodeToString()
        }
    }

    private suspend fun bundle(path: String, hash: String): JsonObject {
        val packed = client.fetchFile(path, maxBytes = MAX_BUNDLE_BYTES)
        return withContext(ioDispatcher) {
            val data = gunzip(packed)
            // The name of a bundle is the hash of its content: what was read is what the index announced.
            if (sha256Hex(data).take(HASH_DIGITS) != hash) appError(AppError.UnexpectedResponse)
            json.parseToJsonElement(data.decodeToString()).jsonObject
        }
    }

    private fun gunzip(packed: ByteArray): ByteArray = GZIPInputStream(packed.inputStream()).use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > MAX_UNPACKED_BYTES) appError(AppError.UnexpectedResponse)
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }

    private companion object {
        const val BUNDLE_SCHEMA = 1
        const val HASH_DIGITS = 32
        const val MAX_BUNDLES = 400
        const val PARALLEL_DOWNLOADS = 4
        const val MAX_BUNDLE_BYTES = 4L * 1024 * 1024
        const val MAX_UNPACKED_BYTES = 16 * 1024 * 1024
        const val BUFFER_BYTES = 64 * 1024
    }
}
