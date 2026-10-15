package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.coroutines.IoDispatcher
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.DebitPredictionInfoSummary
import fr.descentecanyon.app.domain.repository.DebitPredictionRepository
import fr.descentecanyon.app.domain.repository.DebitPredictionSupportRepository
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
class DebitPredictionRepositoryImpl @Inject constructor(
    private val supportRepository: DebitPredictionSupportRepository,
    private val modelStore: EmbeddedDebitModelStore,
    private val featureBuilder: DebitFeatureBuilder,
    private val predictor: OnnxDebitPredictor,
    private val predictionCache: PredictionCache,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DebitPredictionRepository {

    override suspend fun getCachedPredictions(canyonId: Int): CanyonDebitPredictions? = withContext(ioDispatcher) {
        predictionCache.read(canyonId)
    }

    override suspend fun getModelSummary(): Result<DebitPredictionInfoSummary> = suspendRunCatching {
        modelStore.getMetricsSummary()
    }

    override suspend fun getPredictions(detail: CanyonDetail): Result<CanyonDebitPredictions> {
        return withContext(ioDispatcher) {
            suspendRunCatching {
                val support = supportRepository.getPredictionSupport(detail).getOrThrow()
                val featureSpec = modelStore.getFeatureSpec()
                val thresholds = modelStore.getThresholds()
                val staticFeatures = modelStore.getStaticFeatureSet(detail.canyon.id)
                val zoneId = runCatching { ZoneId.of(support.timezone) }.getOrDefault(ZoneId.of("UTC"))
                val baseDate = LocalDate.now(zoneId)
                val horizonDates = (0..2).map { horizonDays ->
                    baseDate.plusDays(horizonDays.toLong())
                }
                val available = horizonDates.mapIndexedNotNull { horizonDays, targetDate ->
                    featureBuilder.buildFeatureVector(
                        detail = detail,
                        support = support,
                        featureSpec = featureSpec,
                        staticFeatureSet = staticFeatures,
                        targetDate = targetDate,
                        horizonDays = horizonDays,
                    )?.let { vector -> Triple(horizonDays, targetDate, vector) }
                }
                check(available.isNotEmpty()) { "Historique météo insuffisant pour estimer le débit" }
                // Today: J0 model. Tomorrow and after: J+1/J+2 model (real-time info known up to today - 1).
                val predictions = available.partition { it.first == 0 }.toList().flatMap { group ->
                    if (group.isEmpty()) return@flatMap emptyList()
                    predictor.predictBatch(
                        featureSpec = featureSpec,
                        thresholds = thresholds,
                        featureVectors = group.map { it.third },
                        dates = group.map { it.second },
                        useHorizonModel = group.first().first > 0,
                    ).mapIndexed { index, prediction -> prediction.copy(horizonDays = group[index].first) }
                }.sortedBy { it.horizonDays }
                CanyonDebitPredictions(
                    target = support.target,
                    timezone = support.timezone,
                    fetchedAt = support.fetchedAt,
                    lookupSource = support.runtimeLookup.lookupSource,
                    usedWeatherCache = support.usedWeatherCache,
                    policy = thresholds.defaultPolicy,
                    ordinalCutpoints = thresholds.ordinalCutpoints,
                    predictions = predictions,
                ).also { predictionCache.save(detail.canyon.id, it) }
            }
        }
    }
}
