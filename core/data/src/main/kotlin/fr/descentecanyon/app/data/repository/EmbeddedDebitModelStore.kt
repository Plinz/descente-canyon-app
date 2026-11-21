package fr.descentecanyon.app.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.domain.coroutines.IoDispatcher
import fr.descentecanyon.app.domain.model.CanyonStaticFeatureSet
import fr.descentecanyon.app.domain.model.DebitFeatureDefinition
import fr.descentecanyon.app.domain.model.DebitFeatureSource
import fr.descentecanyon.app.domain.model.DebitFeatureSpec
import fr.descentecanyon.app.domain.model.DebitNetworkAsset
import fr.descentecanyon.app.domain.model.DebitPredictionDriver
import fr.descentecanyon.app.domain.model.DebitPredictionInfoSummary
import fr.descentecanyon.app.domain.model.DebitPredictionPolicy
import fr.descentecanyon.app.domain.model.DebitThresholds
import fr.descentecanyon.app.domain.model.HorizonModelThresholds
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

@Singleton
class EmbeddedDebitModelStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val json: Json,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val assetFileCache: DebitAssetFileCache,
) {

    private val specMutex = Mutex()
    private val thresholdsMutex = Mutex()
    private val staticFeaturesMutex = Mutex()
    private val metricsMutex = Mutex()
    private val modelMutex = Mutex()

    @Volatile
    private var cachedFeatureSpec: DebitFeatureSpec? = null

    @Volatile
    private var cachedThresholds: DebitThresholds? = null

    @Volatile
    private var cachedStaticFeatureIndex: Map<Int, StaticFeatureIndexEntry>? = null

    @Volatile
    private var cachedStaticFeatureFile: File? = null

    @Volatile
    private var cachedStaticFeatureByCanyonId: Map<Int, CanyonStaticFeatureSet> = emptyMap()

    @Volatile
    private var cachedMetricsSummary: DebitPredictionInfoSummary? = null

    @Volatile
    private var cachedModel: DebitModel? = null

    @Volatile
    private var cachedHorizonModel: DebitModel? = null

    suspend fun getFeatureSpec(): DebitFeatureSpec {
        cachedFeatureSpec?.let { return it }
        return specMutex.withLock {
            cachedFeatureSpec?.let { return it }
            loadJsonAsset<FeatureSpecDto>(FEATURE_SPEC_ASSET_PATH).toDomain().also { cachedFeatureSpec = it }
        }
    }

    suspend fun getThresholds(): DebitThresholds {
        cachedThresholds?.let { return it }
        return thresholdsMutex.withLock {
            cachedThresholds?.let { return it }
            loadJsonAsset<ThresholdsDto>(THRESHOLDS_ASSET_PATH).toDomain().also { cachedThresholds = it }
        }
    }

    suspend fun getStaticFeatureSet(canyonId: Int): CanyonStaticFeatureSet? {
        cachedStaticFeatureByCanyonId[canyonId]?.let { return it }
        return staticFeaturesMutex.withLock {
            cachedStaticFeatureByCanyonId[canyonId]?.let { return it }
            val index = loadStaticFeatureIndex()
            val entry = index[canyonId] ?: return@withLock null
            val assetFile = cachedStaticFeatureFile ?: withContext(ioDispatcher) {
                assetFileCache.file(STATIC_FEATURES_ASSET_PATH, "static-features")
            }.also { cachedStaticFeatureFile = it }

            withContext(ioDispatcher) {
                RandomAccessFile(assetFile, "r").use { randomAccessFile ->
                    randomAccessFile.seek(entry.startByteOffset)
                    val payload = ByteArray(entry.byteLength)
                    randomAccessFile.readFully(payload)
                    val jsonObject = json.decodeFromString<JsonObject>(payload.decodeToString())
                    CanyonStaticFeatureSet(
                        canyonId = canyonId,
                        featureValues = jsonObject.toNumericMap(),
                    )
                }
            }.also { featureSet ->
                cachedStaticFeatureByCanyonId = cachedStaticFeatureByCanyonId + (canyonId to featureSet)
            }
        }
    }

    suspend fun getMetricsSummary(): DebitPredictionInfoSummary {
        cachedMetricsSummary?.let { return it }
        return metricsMutex.withLock {
            cachedMetricsSummary?.let { return it }
            loadJsonAsset<MetricsDto>(METRICS_ASSET_PATH).toDomain().also { cachedMetricsSummary = it }
        }
    }

    /** J+1 / J+2 model (assets named by thresholds.json). */
    suspend fun getHorizonModel(horizon: HorizonModelThresholds): DebitModel {
        cachedHorizonModel?.let { return it }
        return modelMutex.withLock {
            cachedHorizonModel?.let { return it }
            loadModel(horizon.modelAsset, horizon.network).also { cachedHorizonModel = it }
        }
    }

    /** J0 model. */
    suspend fun getModel(): DebitModel {
        cachedModel?.let { return it }
        val network = getThresholds().network
        return modelMutex.withLock {
            cachedModel?.let { return it }
            loadModel(MODEL_ASSET_PATH, network).also { cachedModel = it }
        }
    }

    private suspend fun loadModel(treeAsset: String, network: DebitNetworkAsset?): DebitModel = withContext(ioDispatcher) {
        DebitModel(
            trees = ObliviousTreeModel.read(readAsset(treeAsset)),
            network = network?.let { NeuralNetworkModel.read(readAsset(it.asset)) },
            networkWeight = network?.weight ?: 0.0,
        )
    }

    private fun readAsset(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }

    private suspend inline fun <reified T> loadJsonAsset(path: String): T {
        val payload = withContext(ioDispatcher) {
            context.assets.open(path).bufferedReader().use { it.readText() }
        }
        return json.decodeFromString(payload)
    }

    private fun JsonObject.toNumericMap(): Map<String, Double> = entries.mapNotNull { (key, value) ->
        when (value) {
            JsonNull -> null

            else -> {
                val primitive = value.jsonPrimitive
                primitive.doubleOrNull?.let { key to it }
                    ?: primitive.booleanOrNull?.let { key to if (it) 1.0 else 0.0 }
            }
        }
    }.toMap()

    private suspend fun loadStaticFeatureIndex(): Map<Int, StaticFeatureIndexEntry> {
        cachedStaticFeatureIndex?.let { return it }
        return loadJsonAsset<Map<String, StaticFeatureIndexEntryDto>>(STATIC_FEATURE_INDEX_ASSET_PATH)
            .mapKeys { (key, _) -> key.toInt() }
            .mapValues { (_, value) -> StaticFeatureIndexEntry(value.start, value.length) }
            .also { cachedStaticFeatureIndex = it }
    }

    private fun FeatureSpecDto.toDomain(): DebitFeatureSpec = DebitFeatureSpec(
        schemaVersion = schemaVersion,
        labels = labels,
        features = features.map { feature ->
            DebitFeatureDefinition(
                name = feature.name,
                source = when (feature.source.lowercase()) {
                    "computed" -> DebitFeatureSource.COMPUTED
                    "static" -> DebitFeatureSource.STATIC
                    "weather" -> DebitFeatureSource.WEATHER
                    "lookup" -> DebitFeatureSource.LOOKUP
                    else -> error("Unknown feature source: ${feature.source}")
                },
                defaultValue = feature.default,
            )
        },
        staticFeatureNames = staticFeatureNames,
        lookupFeatureNames = lookupFeatureNames,
        dynamicFeatureNames = dynamicFeatureNames,
    )

    private fun ThresholdsDto.toDomain(): DebitThresholds = DebitThresholds(
        targetMode = targetMode,
        defaultPolicy = defaultPolicy.toPredictionPolicy(),
        highThresholdByPolicy = policies.mapKeys { (key, _) -> key.toPredictionPolicy() }
            .mapValues { (_, value) -> value.highThreshold },
        cumulativeThresholds = cumulativeThresholds,
        ordinalCutpoints = ordinalCutpoints?.orderedValues().orEmpty(),
        horizonModel = horizonModel?.let { horizon ->
            HorizonModelThresholds(
                modelAsset = horizon.modelAsset,
                highThresholdByPolicy = horizon.policies.mapKeys { (key, _) -> key.toPredictionPolicy() }
                    .mapValues { (_, value) -> value.highThreshold },
                cumulativeThresholds = horizon.cumulativeThresholds,
                network = horizon.network?.toDomain(),
            )
        },
        network = network?.toDomain(),
    )

    private fun NetworkDto.toDomain(): DebitNetworkAsset = DebitNetworkAsset(asset = asset, weight = weight)

    private fun MetricsDto.toDomain(): DebitPredictionInfoSummary = DebitPredictionInfoSummary(
        modelName = model,
        targetMode = targetMode,
        featureCount = featureCount,
        trainRowCount = trainRowCount,
        calibrationRowCount = calibrationRowCount,
        testRowCount = testRowCount,
        canyonCount = runtimeLookupMetadata?.canyonCount ?: 0,
        regionCount = runtimeLookupMetadata?.regionCount ?: 0,
        massifCount = runtimeLookupMetadata?.massifCount ?: 0,
        topDrivers = topFeatureImportances
            .mapNotNull { feature -> feature.feature.toDriverOrNull() }
            .distinct()
            .take(5),
    )

    private fun String.toDriverOrNull(): DebitPredictionDriver? = when {
        startsWith("hist") -> DebitPredictionDriver.CANYON_HISTORY

        startsWith("massif") || startsWith("region") -> DebitPredictionDriver.AREA_CONTEXT

        startsWith("wPrecip") || startsWith("hLiquid") || startsWith("hQ") || startsWith("hZQ") ||
            startsWith("hDelta") || startsWith("hSoil") -> DebitPredictionDriver.RECENT_RAIN

        startsWith("wTemp") || startsWith("hSwe") || startsWith("hSnow") || startsWith("hMelt") ->
            DebitPredictionDriver.TEMPERATURE_SNOW

        startsWith("doy") -> DebitPredictionDriver.SEASON

        startsWith("hydro") || startsWith("area") || startsWith("basin") || startsWith("fraction") ||
            this == "karstIndicator" || this == "carbonateFraction" || this == "glacierFraction" ->
            DebitPredictionDriver.WATERSHED

        else -> null
    }

    private fun String.toPredictionPolicy(): DebitPredictionPolicy = when (lowercase()) {
        "balanced" -> DebitPredictionPolicy.BALANCED
        "prudent" -> DebitPredictionPolicy.PRUDENT
        "safety_first" -> DebitPredictionPolicy.SAFETY_FIRST
        else -> error("Unknown threshold policy: $this")
    }

    companion object {
        private const val FEATURE_SPEC_ASSET_PATH = "feature_spec.json"
        private const val THRESHOLDS_ASSET_PATH = "thresholds.json"
        private const val STATIC_FEATURES_ASSET_PATH = "canyon_static_features.json"
        private const val STATIC_FEATURE_INDEX_ASSET_PATH = "canyon_static_features.index.json"
        private const val METRICS_ASSET_PATH = "metrics.json"
        private const val MODEL_ASSET_PATH = "model.trees"
    }
}

private data class StaticFeatureIndexEntry(val startByteOffset: Long, val byteLength: Int)

@Serializable
private data class FeatureSpecDto(
    val schemaVersion: Int,
    val labels: List<String> = emptyList(),
    val features: List<FeatureDto> = emptyList(),
    val staticFeatureNames: List<String> = emptyList(),
    val lookupFeatureNames: List<String> = emptyList(),
    val dynamicFeatureNames: List<String> = emptyList(),
)

@Serializable
private data class FeatureDto(val name: String, val source: String, val default: Double)

@Serializable
private data class ThresholdsDto(
    val targetMode: String = "six_ordinal_cumulative",
    val defaultPolicy: String,
    val policies: Map<String, ThresholdPolicyDto> = emptyMap(),
    val cumulativeThresholds: List<Double> = emptyList(),
    val ordinalCutpoints: OrdinalCutpointsDto? = null,
    val horizonModel: HorizonModelDto? = null,
    val network: NetworkDto? = null,
)

@Serializable
private data class HorizonModelDto(
    val modelAsset: String = "model_horizon.trees",
    val policies: Map<String, ThresholdPolicyDto> = emptyMap(),
    val cumulativeThresholds: List<Double> = emptyList(),
    val network: NetworkDto? = null,
)

@Serializable
private data class NetworkDto(val asset: String, val weight: Double)

@Serializable
private data class ThresholdPolicyDto(val highThreshold: Double)

@Serializable
private data class OrdinalCutpointsDto(
    val secFilet: Double,
    val filetCorrect: Double,
    val correctGros: Double,
    val grosTresGros: Double,
    val tresGrosCrue: Double,
) {
    fun orderedValues(): List<Double> = listOf(secFilet, filetCorrect, correctGros, grosTresGros, tresGrosCrue)
}

@Serializable
private data class MetricsDto(
    val model: String,
    val targetMode: String,
    val featureCount: Int,
    val trainRowCount: Int,
    val calibrationRowCount: Int,
    val testRowCount: Int,
    val topFeatureImportances: List<FeatureImportanceDto> = emptyList(),
    val runtimeLookupMetadata: RuntimeLookupMetadataDto? = null,
)

@Serializable
private data class FeatureImportanceDto(val feature: String)

@Serializable
private data class RuntimeLookupMetadataDto(val regionCount: Int, val massifCount: Int, val canyonCount: Int)

@Serializable
private data class StaticFeatureIndexEntryDto(val start: Long, val length: Int)
