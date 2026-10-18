package fr.descentecanyon.app.data.repository

import ai.onnxruntime.OnnxMap
import ai.onnxruntime.OnnxSequence
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import fr.descentecanyon.app.domain.coroutines.IoDispatcher
import fr.descentecanyon.app.domain.model.DailyDebitPrediction
import fr.descentecanyon.app.domain.model.DebitFeatureSpec
import fr.descentecanyon.app.domain.model.DebitPredictionPolicy
import fr.descentecanyon.app.domain.model.DebitThresholds
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.PredictedDebitLevel
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
class OnnxDebitPredictor @Inject constructor(
    private val modelStore: EmbeddedDebitModelStore,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun predictBatch(
        featureSpec: DebitFeatureSpec,
        thresholds: DebitThresholds,
        featureVectors: List<FloatArray>,
        dates: List<java.time.LocalDate>,
        policy: DebitPredictionPolicy = thresholds.defaultPolicy,
        useHorizonModel: Boolean = false,
    ): List<DailyDebitPrediction> {
        require(featureVectors.isNotEmpty()) { "featureVectors must not be empty" }
        require(featureVectors.size == dates.size) { "featureVectors and dates must have the same size" }
        val horizon = thresholds.horizonModel?.takeIf { useHorizonModel }

        return withContext(ioDispatcher) {
            val sessionHolder = horizon?.let { modelStore.getHorizonSession(it.modelAsset) } ?: modelStore.getSession()
            val inputName = sessionHolder.session.inputNames.firstOrNull()
                ?: error("No ONNX input found")
            val featureCount = featureVectors.first().size
            require(featureVectors.all { it.size == featureCount }) { "All feature vectors must have the same size" }

            val flatBuffer = FloatArray(featureVectors.size * featureCount)
            featureVectors.forEachIndexed { rowIndex, vector ->
                vector.copyInto(
                    destination = flatBuffer,
                    destinationOffset = rowIndex * featureCount,
                )
            }

            val tensor = OnnxTensor.createTensor(
                sessionHolder.environment,
                FloatBuffer.wrap(flatBuffer),
                longArrayOf(featureVectors.size.toLong(), featureCount.toLong()),
            )
            tensor.use { inputTensor ->
                sessionHolder.session.run(mapOf(inputName to inputTensor)).use { result ->
                    val probabilitiesByRow = extractProbabilityRows(result, featureSpec.labels)
                    require(probabilitiesByRow.size == featureVectors.size) {
                        "Expected ${featureVectors.size} prediction rows, got ${probabilitiesByRow.size}"
                    }

                    val highThreshold = (horizon?.highThresholdByPolicy ?: thresholds.highThresholdByPolicy)[policy]
                        ?: error("Missing threshold for policy $policy")
                    val cumulativeThresholds = horizon?.cumulativeThresholds ?: thresholds.cumulativeThresholds
                    require(cumulativeThresholds.size == ORDINAL_LABELS.size - 1) {
                        "Expected ${ORDINAL_LABELS.size - 1} cumulative thresholds, got ${cumulativeThresholds.size}"
                    }

                    probabilitiesByRow.mapIndexed { index, probabilitiesByLabel ->
                        val distribution = DoubleArray(ORDINAL_LABELS.size) { rank ->
                            probabilitiesByLabel[ORDINAL_LABELS[rank]] ?: 0.0
                        }
                        val decision = decideOrdinal(distribution, cumulativeThresholds)
                        val highProbability = decision.cumulative[HIGH_RANK]
                        val level = when {
                            decision.level >= HIGH_RANK || highProbability >= highThreshold -> PredictedDebitLevel.HIGH
                            decision.level <= LOW_RANK -> PredictedDebitLevel.LOW
                            else -> PredictedDebitLevel.MEDIUM
                        }
                        DailyDebitPrediction(
                            date = dates[index],
                            horizonDays = index,
                            level = level,
                            probabilities = mapOf(
                                PredictedDebitLevel.LOW to distribution[0] + distribution[1],
                                PredictedDebitLevel.MEDIUM to distribution[2],
                                PredictedDebitLevel.HIGH to highProbability,
                            ),
                            highThreshold = highThreshold,
                            ordinalScore = decision.displayScore,
                            ordinalLevel = ORDINAL_LEVELS[decision.level],
                            levelProbabilities = ORDINAL_LEVELS.withIndex().associate { (rank, level) -> level to distribution[rank] },
                        )
                    }
                }
            }
        }
    }

    suspend fun predict(
        featureSpec: DebitFeatureSpec,
        thresholds: DebitThresholds,
        featureVector: FloatArray,
        horizonDays: Int,
        date: java.time.LocalDate,
        policy: DebitPredictionPolicy = thresholds.defaultPolicy,
    ): DailyDebitPrediction = predictBatch(
        featureSpec = featureSpec,
        thresholds = thresholds,
        featureVectors = listOf(featureVector),
        dates = listOf(date),
        policy = policy,
    ).single().copy(horizonDays = horizonDays)

    internal class OrdinalDecision(
        val level: Int,
        /** cumulative[k] = P(rank >= k), cumulative[0] = 1. */
        val cumulative: DoubleArray,
        val displayScore: Double,
    )

    private fun extractProbabilityRows(result: ai.onnxruntime.OrtSession.Result, labels: List<String>): List<Map<String, Double>> {
        result.forEach { (_, value) ->
            extractProbabilityRows(value, labels)?.let { return it }
        }
        error("No probability output found in ONNX result")
    }

    private fun extractProbabilityRows(value: OnnxValue, labels: List<String>): List<Map<String, Double>>? = when (value) {
        is OnnxMap -> parseProbabilityMap(value.value)?.let(::listOf)
        is OnnxSequence -> extractProbabilitiesFromRaw(value.value, labels)
        is OnnxTensor -> parseTensorProbabilities(value.value, labels)
        else -> null
    }

    private fun extractProbabilitiesFromRaw(raw: Any?, labels: List<String>): List<Map<String, Double>>? = when (raw) {
        is OnnxMap -> parseProbabilityMap(raw.value)?.let(::listOf)
        is Map<*, *> -> parseProbabilityMap(raw)?.let(::listOf)
        is List<*> -> raw.mapNotNull { item -> extractProbabilitiesFromRaw(item, labels) }.flatten().takeIf { it.isNotEmpty() }
        is Array<*> -> raw.mapNotNull { item -> extractProbabilitiesFromRaw(item, labels) }.flatten().takeIf { it.isNotEmpty() }
        else -> null
    }

    private fun parseProbabilityMap(raw: Map<*, *>): Map<String, Double>? {
        val probabilities = raw.entries.mapNotNull { entry ->
            val label = entry.key?.toString() ?: return@mapNotNull null
            val probability = (entry.value as? Number)?.toDouble() ?: return@mapNotNull null
            label to probability
        }.toMap()
        return probabilities.takeIf { it.isNotEmpty() }
    }

    private fun parseTensorProbabilities(raw: Any?, labels: List<String>): List<Map<String, Double>>? = when (raw) {
        is Array<*> -> when (val first = raw.firstOrNull()) {
            is FloatArray -> raw.mapNotNull { row ->
                (row as? FloatArray)?.let { labels.zip(it.map { value -> value.toDouble() }).toMap() }
            }.takeIf { it.isNotEmpty() }

            is DoubleArray -> raw.mapNotNull { row ->
                (row as? DoubleArray)?.let { labels.zip(it.toList()).toMap() }
            }.takeIf { it.isNotEmpty() }

            else -> null
        }

        is FloatArray -> listOf(labels.zip(raw.map { it.toDouble() }).toMap())

        is DoubleArray -> listOf(labels.zip(raw.toList()).toMap())

        else -> null
    }

    companion object {
        private const val HIGH_RANK = 3
        private const val LOW_RANK = 1
        private val ORDINAL_LABELS = listOf("SEC", "FILET", "CORRECT", "GROS", "TRES_GROS", "CRUE")
        private val ORDINAL_LEVELS = listOf(
            NiveauDebit.SEC,
            NiveauDebit.FILET,
            NiveauDebit.CORRECT,
            NiveauDebit.GROS,
            NiveauDebit.TRES_GROS,
            NiveauDebit.CRUE,
        )

        /**
         * Port of `evaluation.decide` / `evaluation.display_score`: the level is the highest k such
         * that P(rank >= j) >= tau_j for every j <= k; the display score stays inside
         * [level - 0.5, level + 0.5) and moves towards the neighbour level it is closest to.
         */
        internal fun decideOrdinal(distribution: DoubleArray, thresholds: List<Double>): OrdinalDecision {
            val levelCount = distribution.size
            val cumulative = DoubleArray(levelCount + 1)
            for (rank in levelCount - 1 downTo 0) cumulative[rank] = cumulative[rank + 1] + distribution[rank]
            cumulative[0] = 1.0
            var level = 0
            for (k in 1 until levelCount) {
                if (cumulative[k] >= thresholds[k - 1]) level = k else break
            }
            val tau = listOf(0.0) + thresholds + listOf(1.0)
            val up = if (level == levelCount - 1) 0.0 else (cumulative[level + 1] / max(tau[level + 1], 1e-9)).coerceIn(0.0, 1.0)
            val down = if (level == 0) 0.0 else ((1.0 - cumulative[level]) / max(1.0 - tau[level], 1e-9)).coerceIn(0.0, 1.0)
            val score = level + (0.45 * (up - down)).coerceIn(-0.49, 0.49)
            return OrdinalDecision(level, cumulative.copyOf(levelCount), score)
        }
    }
}
