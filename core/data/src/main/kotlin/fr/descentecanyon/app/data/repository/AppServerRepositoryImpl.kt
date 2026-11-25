package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.AppBuildInfo
import fr.descentecanyon.app.data.BackendConfig
import fr.descentecanyon.app.data.local.catalog.CatalogDownloader
import fr.descentecanyon.app.data.local.catalog.CatalogInstaller
import fr.descentecanyon.app.data.local.catalog.CatalogUpdateStore
import fr.descentecanyon.app.data.local.catalog.toInstantOrNull
import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.data.remote.backend.BackendClient
import fr.descentecanyon.app.data.remote.backend.ManifestMessageDto
import fr.descentecanyon.app.data.remote.backend.ManifestPayloadDto
import fr.descentecanyon.app.data.remote.backend.ManifestVerifier
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.AppServerState
import fr.descentecanyon.app.domain.model.CatalogSource
import fr.descentecanyon.app.domain.model.CatalogStatus
import fr.descentecanyon.app.domain.model.ServerFeatures
import fr.descentecanyon.app.domain.model.ServerMessage
import fr.descentecanyon.app.domain.model.ServerMessageLevel
import fr.descentecanyon.app.domain.repository.AppServerRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Plans the work that must outlive the screen: the download of a newer catalog (WorkManager). */
interface CatalogUpdateScheduler {
    fun scheduleDownload(allowMobileData: Boolean)

    fun cancelDownload()
}

@Singleton
class AppServerRepositoryImpl @Inject constructor(
    private val config: BackendConfig,
    private val buildInfo: AppBuildInfo,
    private val client: BackendClient,
    private val keyValueDao: KeyValueDao,
    private val json: Json,
    private val clock: Clock,
    private val catalogInstaller: CatalogInstaller,
    private val catalogUpdates: CatalogUpdateStore,
    private val catalogDownloader: CatalogDownloader,
    private val scheduler: CatalogUpdateScheduler,
) : AppServerRepository {

    private val verifier by lazy { ManifestVerifier(config.publicKeys, json) }

    override fun observeState(): Flow<AppServerState> = combine(
        observe(KEY_SETTINGS, StoredSettings.serializer()).map { it ?: StoredSettings() },
        observe(KEY_MANIFEST, StoredManifest.serializer()),
        catalogUpdates.snapshot,
    ) { settings, stored, snapshot ->
        val payload = stored?.payload
        AppServerState(
            isConfigured = config.isConfigured,
            isEnabled = settings.enabled,
            catalogOnMobileData = settings.catalogOnMobileData,
            lastCheckedAt = stored?.checkedAtMs?.let(Instant::ofEpochMilli),
            lastCheckFailed = stored?.failed == true,
            updateRequired = payload != null && buildInfo.versionCode < payload.minVersionCode,
            messages = payload?.messages.orEmpty().filter { isShown(it) && it.id !in settings.dismissedMessages }.map { it.toDomain() },
            features = ServerFeatures(
                communityTracks = payload?.features?.get(FEATURE_TRACKS) ?: true,
                catalogUpdates = payload?.features?.get(FEATURE_CATALOG) ?: true,
            ),
            catalog = CatalogStatus(
                source = if (snapshot.installedServerVersion != null) CatalogSource.SERVER else CatalogSource.APP,
                dataVersion = snapshot.installedServerVersion ?: catalogInstaller.embeddedVersion.takeIf { it != Instant.EPOCH },
                pendingDataVersion = snapshot.staged?.dataVersion,
            ),
        )
    }.distinctUntilChanged()

    override suspend fun refresh(): Result<Unit> {
        if (!config.isConfigured) return Result.success(Unit)
        val settings = load(KEY_SETTINGS, StoredSettings.serializer()) ?: StoredSettings()
        if (!settings.enabled) return Result.success(Unit)
        val previous = load(KEY_MANIFEST, StoredManifest.serializer())
        return suspendRunCatching {
            val received = verifier.verify(client.fetchManifest())
            // A replayed older manifest is ignored: it could revive a message or a switch.
            val accepted = previous?.payload?.takeIf { it.sequence > received.sequence } ?: received
            save(KEY_MANIFEST, StoredManifest.serializer(), StoredManifest(accepted, clock.millis(), failed = false))
            if ((accepted.features[FEATURE_CATALOG] ?: true) && catalogDownloader.pick(accepted.catalogs) != null) {
                scheduler.scheduleDownload(allowMobileData = settings.catalogOnMobileData)
            }
        }.onFailure {
            save(
                KEY_MANIFEST,
                StoredManifest.serializer(),
                (previous ?: StoredManifest()).copy(checkedAtMs = clock.millis(), failed = true),
            )
        }
    }

    /** Downloads the catalog the last accepted manifest announces, when it is still newer (worker). */
    suspend fun downloadAnnouncedCatalog(): Result<Boolean> {
        if (!config.isConfigured) return Result.success(false)
        val settings = load(KEY_SETTINGS, StoredSettings.serializer()) ?: StoredSettings()
        val payload = load(KEY_MANIFEST, StoredManifest.serializer())?.payload
        if (!settings.enabled || payload == null || payload.features[FEATURE_CATALOG] == false) return Result.success(false)
        val entry = catalogDownloader.pick(payload.catalogs) ?: return Result.success(false)
        return catalogDownloader.download(entry).map { true }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        val settings = load(KEY_SETTINGS, StoredSettings.serializer()) ?: StoredSettings()
        save(KEY_SETTINGS, StoredSettings.serializer(), settings.copy(enabled = enabled))
        if (!enabled) {
            // Switched off: nothing more is downloaded or installed (an installed catalog is kept: it is only newer data).
            scheduler.cancelDownload()
            catalogUpdates.clearStaged()
            catalogUpdates.deleteLeftovers()
        }
    }

    override suspend fun setCatalogOnMobileData(enabled: Boolean) {
        val settings = load(KEY_SETTINGS, StoredSettings.serializer()) ?: StoredSettings()
        save(KEY_SETTINGS, StoredSettings.serializer(), settings.copy(catalogOnMobileData = enabled))
        val payload = load(KEY_MANIFEST, StoredManifest.serializer())?.payload
        if (settings.enabled && payload != null && catalogDownloader.pick(payload.catalogs) != null) {
            scheduler.scheduleDownload(allowMobileData = enabled)
        }
    }

    override suspend fun dismissMessage(id: String) {
        val settings = load(KEY_SETTINGS, StoredSettings.serializer()) ?: StoredSettings()
        save(KEY_SETTINGS, StoredSettings.serializer(), settings.copy(dismissedMessages = settings.dismissedMessages + id))
    }

    private fun isShown(message: ManifestMessageDto): Boolean {
        if (buildInfo.versionCode < message.minVersionCode) return false
        if (message.maxVersionCode != null && buildInfo.versionCode > message.maxVersionCode) return false
        val until = message.until?.let {
            it.toInstantOrNull()
                ?: runCatching { LocalDate.parse(it).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
        }
        if (until != null && !clock.instant().isBefore(until)) return false
        return message.text.isNotEmpty()
    }

    private fun ManifestMessageDto.toDomain() = ServerMessage(
        id = id,
        level = if (level == "warning") ServerMessageLevel.WARNING else ServerMessageLevel.INFO,
        texts = text,
    )

    private fun <T> observe(key: String, serializer: KSerializer<T>): Flow<T?> = keyValueDao.observe(key).map { decode(it, serializer) }

    private suspend fun <T> load(key: String, serializer: KSerializer<T>): T? = decode(keyValueDao.get(key), serializer)

    private suspend fun <T> save(key: String, serializer: KSerializer<T>, value: T) {
        keyValueDao.put(KeyValueEntity(key, json.encodeToString(serializer, value)))
    }

    private fun <T> decode(payload: String?, serializer: KSerializer<T>): T? =
        payload?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    @Serializable
    private data class StoredSettings(
        val enabled: Boolean = true,
        val catalogOnMobileData: Boolean = false,
        val dismissedMessages: Set<String> = emptySet(),
    )

    @Serializable
    private data class StoredManifest(val payload: ManifestPayloadDto? = null, val checkedAtMs: Long? = null, val failed: Boolean = false)

    private companion object {
        const val KEY_SETTINGS = "app_server_settings"
        const val KEY_MANIFEST = "app_server_manifest"
        const val FEATURE_TRACKS = "communityTracks"
        const val FEATURE_CATALOG = "catalogUpdates"
    }
}
