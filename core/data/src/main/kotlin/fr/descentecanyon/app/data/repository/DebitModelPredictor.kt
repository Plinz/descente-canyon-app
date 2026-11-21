package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.coroutines.IoDispatcher
import fr.descentecanyon.app.domain.model.DailyDebitPrediction
import fr.descentecanyon.app.domain.model.DebitFeatureSpec
import fr.descentecanyon.app.domain.model.DebitPredictionPolicy
import fr.descentecanyon.app.domain.model.DebitThresholds
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.PredictedDebitLevel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Runs the embedded debit models ([DebitModel]: CatBoost trees averaged with a network) and applies the ordinal decision. */
@Singleton
class DebitModelPredictor @Inject constructor(
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
            val model = horizon?.let { modelStore.getHorizonModel(it) } ?: modelStore.getModel()
            require(featureSpec.labels.size == model.classCount) {
                "Expected ${model.classCount} labels, got ${featureSpec.labels.size}"
            }
            val rankOfLabel = ORDINAL_LABELS.map { label -> featureSpec.labels.indexOf(label) }

            val highThreshold = (horizon?.highThresholdByPolicy ?: thresholds.highThresholdByPolicy)[policy]
                ?: error("Missing threshold for policy $policy")
            val cumulativeThresholds = horizon?.cumulativeThresholds ?: thresholds.cumulativeThresholds
            require(cumulativeThresholds.size == ORDINAL_LABELS.size - 1) {
                "Expected ${ORDINAL_LABELS.size - 1} cumulative thresholds, got ${cumulativeThresholds.size}"
            }

            model.predictProbabilities(featureVectors).mapIndexed { index, probabilities ->
                val distribution = DoubleArray(ORDINAL_LABELS.size) { rank ->
                    rankOfLabel[rank].takeIf { it >= 0 }?.let { probabilities[it] } ?: 0.0
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
