package fr.descentecanyon.app.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.BuildConfig
import fr.descentecanyon.app.di.IoDispatcher
import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.NeighborCanyon
import fr.descentecanyon.app.domain.model.RainStation
import fr.descentecanyon.app.domain.model.ResolvedRuntimeFeatureLookup
import fr.descentecanyon.app.domain.model.RuntimeLookupEntry
import fr.descentecanyon.app.domain.model.RuntimeLookupSource
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

@Singleton
class EmbeddedDebitRuntimeLookupStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val json: Json,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val coreLoadMutex = Mutex()
    private val canyonLoadMutex = Mutex()

    @Volatile
    private var cachedCore: RuntimeLookupCore? = null

    @Volatile
    private var cachedCanyonIndex: Map<Int, RuntimeLookupIndexEntry>? = null

    @Volatile
    private var cachedCanyonFile: File? = null

    @Volatile
    private var cachedCanyonEntries: Map<Int, RuntimeLookupEntry?> = emptyMap()

    suspend fun resolve(canyon: Canyon): ResolvedRuntimeFeatureLookup {
        val core = getCoreLookups()
        val resolvedRegionKey = canyon.region ?: core.defaultRegionKey
        val resolvedMassifKey = canyon.massif ?: core.defaultMassifKey
        val canyonEntry = getCanyonEntry(canyon.id, core.defaultRegionKey, core.defaultMassifKey)
        val regionValues = core.regions[resolvedRegionKey].orEmpty()
        val massifValues = core.massifs[resolvedMassifKey].orEmpty()

        val featureValues = buildMap {
            putAll(core.defaults)
            putAll(core.global)
            putAll(regionValues)
            putAll(massifValues)
            if (canyonEntry != null) {
                putAll(canyonEntry.values)
            }
        }

        val lookupSource = when {
            canyonEntry != null -> RuntimeLookupSource.CANYON
            massifValues.isNotEmpty() -> RuntimeLookupSource.MASSIF
            regionValues.isNotEmpty() -> RuntimeLookupSource.REGION
            else -> RuntimeLookupSource.GLOBAL
        }

        val neighbors = canyonEntry?.values.orEmpty().mapNotNull { (key, distance) ->
            val neighborId = key.takeIf { it.length > 1 && it[0] == 'n' && it.drop(1).all(Char::isDigit) }
                ?.drop(1)?.toIntOrNull() ?: return@mapNotNull null
            val aggregates = core.cellSeasonal[neighborId]
            NeighborCanyon(
                canyonId = neighborId,
                distanceKm = distance,
                monthlyCounts = aggregates?.copyOfRange(0, 12) ?: DoubleArray(12),
                monthlySums = aggregates?.copyOfRange(12, 24) ?: DoubleArray(12),
            )
        }.sortedBy { it.distanceKm }
        val rainStations = canyonEntry?.values.orEmpty().mapNotNull { (key, distance) ->
            key.takeIf(::isRainStationKey)?.let { RainStation(code = it.drop(2), distanceKm = distance) }
        }.sortedBy { it.distanceKm }

        return ResolvedRuntimeFeatureLookup(
            canyonId = canyon.id,
            regionKey = canyonEntry?.regionKey ?: resolvedRegionKey,
            massifKey = canyonEntry?.massifKey ?: resolvedMassifKey,
            lookupSource = lookupSource,
            featureValues = featureValues,
            neighbors = neighbors,
            rainStations = rainStations,
        )
    }

    /** `mf<code>`: Météo-France codes are digits, MeteoSwiss ones start with `CHS_` / `CHP_`. */
    private fun isRainStationKey(key: String): Boolean {
        if (key.length <= 2 || !key.startsWith("mf")) return false
        val code = key.drop(2)
        return code.all(Char::isDigit) || code.startsWith("CHS_") || code.startsWith("CHP_")
    }

    private suspend fun getCoreLookups(): RuntimeLookupCore {
        cachedCore?.let { return it }
        return coreLoadMutex.withLock {
            cachedCore?.let { return it }
            val dto = loadJsonAsset<RuntimeLookupCoreDto>(RUNTIME_LOOKUPS_CORE_ASSET_PATH)
            dto.toDomain().also { cachedCore = it }
        }
    }

    private suspend fun getCanyonEntry(
        canyonId: Int,
        defaultRegionKey: String,
        defaultMassifKey: String,
    ): RuntimeLookupEntry? {
        cachedCanyonEntries[canyonId]?.let { return it }
        if (cachedCanyonEntries.containsKey(canyonId)) return null

        return canyonLoadMutex.withLock {
            cachedCanyonEntries[canyonId]?.let { return it }
            if (cachedCanyonEntries.containsKey(canyonId)) return@withLock null

            val index = loadCanyonIndex()
            val indexEntry = index[canyonId] ?: run {
                cachedCanyonEntries = cachedCanyonEntries + (canyonId to null)
                return@withLock null
            }
            val assetFile = cachedCanyonFile ?: withContext(ioDispatcher) {
                ensureAssetFile(
                    assetPath = RUNTIME_LOOKUPS_CANYONS_ASSET_PATH,
                    targetFileName = "runtime-lookups-canyons-v${BuildConfig.VERSION_CODE}.json",
                )
            }.also { cachedCanyonFile = it }

            withContext(ioDispatcher) {
                RandomAccessFile(assetFile, "r").use { randomAccessFile ->
                    randomAccessFile.seek(indexEntry.startByteOffset)
                    val payload = ByteArray(indexEntry.byteLength)
                    randomAccessFile.readFully(payload)
                    val jsonObject = json.decodeFromString<JsonObject>(payload.decodeToString())
                    RuntimeLookupEntry(
                        regionKey = jsonObject["regionKey"]?.jsonPrimitive?.content ?: defaultRegionKey,
                        massifKey = jsonObject["massifKey"]?.jsonPrimitive?.content ?: defaultMassifKey,
                        values = jsonObject.toNumericMap(),
                    )
                }
            }.also { runtimeLookupEntry ->
                cachedCanyonEntries = cachedCanyonEntries + (canyonId to runtimeLookupEntry)
            }
        }
    }

    private suspend fun loadCanyonIndex(): Map<Int, RuntimeLookupIndexEntry> {
        cachedCanyonIndex?.let { return it }
        return loadJsonAsset<Map<String, RuntimeLookupIndexEntryDto>>(RUNTIME_LOOKUPS_CANYON_INDEX_ASSET_PATH)
            .mapKeys { (key, _) -> key.toInt() }
            .mapValues { (_, value) -> RuntimeLookupIndexEntry(value.start, value.length) }
            .also { cachedCanyonIndex = it }
    }

    private suspend inline fun <reified T> loadJsonAsset(path: String): T {
        val payload = withContext(ioDispatcher) {
            context.assets.open(path).bufferedReader().use { it.readText() }
        }
        return json.decodeFromString(payload)
    }

    private fun ensureAssetFile(assetPath: String, targetFileName: String): File {
        val targetDir = File(context.filesDir, "debit-model-cache")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        val targetFile = File(targetDir, targetFileName)
        if (!targetFile.exists() || targetFile.length() == 0L) {
            context.assets.open(assetPath).use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return targetFile
    }

    private fun RuntimeLookupCoreDto.toDomain(): RuntimeLookupCore {
        return RuntimeLookupCore(
            schemaVersion = schemaVersion,
            labels = labels,
            lookupFeatureNames = lookupFeatureNames,
            defaultRegionKey = unknownKeys.region,
            defaultMassifKey = unknownKeys.massif,
            defaults = defaults.toNumericMap(),
            global = global.toNumericMap(),
            regions = regions.mapValues { (_, value) -> value.toNumericMap() },
            massifs = massifs.mapValues { (_, value) -> value.toNumericMap() },
            cellSeasonal = cellSeasonal.mapNotNull { (key, values) ->
                key.toIntOrNull()?.takeIf { values.size == 24 }?.let { it to values.toDoubleArray() }
            }.toMap(),
        )
    }

    private fun JsonObject.toNumericMap(): Map<String, Double> {
        return entries.mapNotNull { (key, value) ->
            when (value) {
                JsonNull -> null
                else -> {
                    val primitive = value.jsonPrimitive
                    primitive.doubleOrNull?.let { key to it }
                        ?: primitive.booleanOrNull?.let { key to if (it) 1.0 else 0.0 }
                }
            }
        }.toMap()
    }

    private companion object {
        private const val RUNTIME_LOOKUPS_CORE_ASSET_PATH = "runtime_feature_lookups.core.json"
        private const val RUNTIME_LOOKUPS_CANYONS_ASSET_PATH = "runtime_feature_lookups.canyons.json"
        private const val RUNTIME_LOOKUPS_CANYON_INDEX_ASSET_PATH = "runtime_feature_lookups.canyons.index.json"
    }
}

private data class RuntimeLookupCore(
    val schemaVersion: Int,
    val labels: List<String>,
    val lookupFeatureNames: List<String>,
    val defaultRegionKey: String,
    val defaultMassifKey: String,
    val defaults: Map<String, Double>,
    val global: Map<String, Double>,
    val regions: Map<String, Map<String, Double>>,
    val massifs: Map<String, Map<String, Double>>,
    val cellSeasonal: Map<Int, DoubleArray>,
)

private data class RuntimeLookupIndexEntry(
    val startByteOffset: Long,
    val byteLength: Int,
)

@Serializable
private data class RuntimeLookupCoreDto(
    val schemaVersion: Int,
    val labels: List<String> = emptyList(),
    val unknownKeys: UnknownKeysDto,
    val lookupFeatureNames: List<String> = emptyList(),
    val defaults: JsonObject = JsonObject(emptyMap()),
    val global: JsonObject = JsonObject(emptyMap()),
    val regions: Map<String, JsonObject> = emptyMap(),
    val massifs: Map<String, JsonObject> = emptyMap(),
    val cellSeasonal: Map<String, List<Double>> = emptyMap(),
)

@Serializable
private data class UnknownKeysDto(
    val region: String,
    val massif: String,
)

@Serializable
private data class RuntimeLookupIndexEntryDto(
    val start: Long,
    val length: Int,
)
