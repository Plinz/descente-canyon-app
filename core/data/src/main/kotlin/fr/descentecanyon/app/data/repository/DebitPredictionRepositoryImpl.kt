package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.coroutines.IoDispatcher
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.AppException
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.AppCanyons
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.DailyDebitPrediction
import fr.descentecanyon.app.domain.model.DebitPredictionBasis
import fr.descentecanyon.app.domain.model.DebitPredictionInfoSummary
import fr.descentecanyon.app.domain.model.ORDINAL_DEBIT_LEVELS
import fr.descentecanyon.app.domain.repository.AppServerRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitPredictionRepository
import fr.descentecanyon.app.domain.repository.DebitPredictionSupportRepository
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class DebitPredictionRepositoryImpl @Inject constructor(
    private val supportRepository: DebitPredictionSupportRepository,
    private val modelStore: EmbeddedDebitModelStore,
    private val featureBuilder: DebitFeatureBuilder,
    private val predictor: DebitModelPredictor,
    private val predictionCache: PredictionCache,
    private val canyonRepository: CanyonRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val serverPredictions: ServerPredictionStore,
    private val appServer: AppServerRepository,
    private val clock: Clock,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DebitPredictionRepository {

    private val backgroundScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** Spaces out the estimates that download a whole weather window (about 29 calls of the Open-Meteo quota each). */
    private val fullDownloadMutex = Mutex()
    private var lastFullDownloadAtMs = 0L

    override fun observeEstimates(canyonIds: List<Int>): Flow<Map<Int, CanyonDebitPredictions>> = flow {
        // No estimate for the canyons added in the app yet (no flow report, no checked watershed).
        val ids = canyonIds.distinct().filterNot(AppCanyons::isAppCanyon)
        val known = LinkedHashMap<Int, CanyonDebitPredictions>()
        ids.forEach { id -> (server(id) ?: predictionCache.read(id))?.let { known[id] = it } }
        emit(known.toMap())
        // The server computes every canyon: a fresher file saves the phone the weather downloads.
        if (ids.any { known[it]?.computedOnServer != true }) {
            refreshServerEstimates()
            val before = known.toMap()
            ids.forEach { id -> server(id)?.let { known[id] = it } }
            if (known != before) emit(known.toMap())
        }
        for (id in ids) {
            if (!connectivityObserver.isCurrentlyOnline()) break
            if (known[id]?.let { it.computedOnServer || it.isRecent() } == true) continue
            val detail = canyonRepository.getCanyonPreview(id).getOrNull()?.takeIf { it.isLocated } ?: continue
            if (!supportRepository.hasWeatherHistory(id)) spaceOutFullDownload()
            getPredictions(detail).onSuccess { predictions ->
                known[id] = predictions
                emit(known.toMap())
            }
        }
    }

    override fun observeServerEstimates(canyonIds: List<Int>, date: LocalDate): Flow<Map<Int, DailyDebitPrediction>> = flow {
        val ids = canyonIds.distinct().filterNot(AppCanyons::isAppCanyon)
        suspend fun read() = withContext(ioDispatcher) { runCatching { serverPredictions.dailyFor(ids, date) }.getOrDefault(emptyMap()) }
        val kept = read()
        emit(kept)
        refreshServerEstimates()
        val fresh = read()
        if (fresh != kept) emit(fresh)
    }

    /** Computed today (in the canyon's time zone) less than [ESTIMATE_REFRESH] ago. */
    private fun CanyonDebitPredictions.isRecent(): Boolean {
        val now = clock.instant()
        val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("UTC"))
        return Duration.between(computedAt, now) < ESTIMATE_REFRESH &&
            computedAt.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
    }

    private suspend fun spaceOutFullDownload() = fullDownloadMutex.withLock {
        val wait = lastFullDownloadAtMs + FULL_DOWNLOAD_SPACING_MS - clock.millis()
        if (wait > 0) delay(wait)
        lastFullDownloadAtMs = clock.millis()
    }

    override suspend fun getCachedPredictions(canyonId: Int): CanyonDebitPredictions? = withContext(ioDispatcher) {
        server(canyonId) ?: predictionCache.read(canyonId)
    }

    override suspend fun getModelSummary(): Result<DebitPredictionInfoSummary> = suspendRunCatching {
        modelStore.getMetricsSummary().copy(liveCheck = withContext(ioDispatcher) { serverPredictions.liveCheck() })
    }

    /** The server's estimate of today, when there is one (reliability from the APK's walk-forward check). */
    private suspend fun server(canyonId: Int): CanyonDebitPredictions? = withContext(ioDispatcher) {
        runCatching { serverPredictions.predictions(canyonId, modelStore.getReliability(canyonId)) }.getOrNull()
    }

    /**
     * The server's estimate unless the canyon page shows a report it did not know: one of the days
     * before today (a report of today is not an input of today's estimate, see DebitFeatureBuilder).
     */
    private suspend fun serverFor(detail: CanyonDetail): CanyonDebitPredictions? {
        val predictions = server(detail.canyon.id) ?: return null
        val zone = runCatching { ZoneId.of(predictions.timezone) }.getOrDefault(ZoneId.of("UTC"))
        val today = LocalDate.now(clock.withZone(zone)).toEpochDay()
        val newest = detail.debits.map { it.date.toEpochDay() }.filter { it < today }.maxOrNull() ?: return predictions
        val known = withContext(ioDispatcher) { serverPredictions.lastReportDay(detail.canyon.id) }
        return predictions.takeIf { known != null && known >= newest }
    }

    override suspend fun getPredictions(detail: CanyonDetail): Result<CanyonDebitPredictions> {
        if (AppCanyons.isAppCanyon(detail.canyon.id)) return Result.failure(AppException(AppError.DataUnavailable))
        refreshServerEstimates()
        serverFor(detail)?.let { return Result.success(it) }
        val computed = computeOnPhone(detail)
        if (computed.isSuccess) return computed
        // The phone could not compute it (weather unreachable...): the server's, even without the newest report.
        return server(detail.canyon.id)?.let { Result.success(it) } ?: computed
    }

    /**
     * Reads the manifest again when it is old, waiting at most [SERVER_WAIT_MS]: an unreachable
     * server only delays the phone's own computation by that much. The check goes on in the
     * background and its estimates serve the next screens.
     */
    private suspend fun refreshServerEstimates() {
        if (!connectivityObserver.isCurrentlyOnline()) return
        val check = backgroundScope.async { appServer.refreshIfOlderThan(SERVER_CHECK_AFTER) }
        withTimeoutOrNull(SERVER_WAIT_MS) { check.await() }
    }

    private suspend fun computeOnPhone(detail: CanyonDetail): Result<CanyonDebitPredictions> {
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
                val features = horizonDates.mapIndexedNotNull { horizonDays, targetDate ->
                    featureBuilder.buildFeatures(
                        detail = detail,
                        support = support,
                        featureSpec = featureSpec,
                        staticFeatureSet = staticFeatures,
                        targetDate = targetDate,
                        horizonDays = horizonDays,
                    )?.let { built -> Triple(horizonDays, targetDate, built) }
                }
                check(features.isNotEmpty()) { "Historique météo insuffisant pour estimer le débit" }
                val available = features.map { (horizonDays, targetDate, built) -> Triple(horizonDays, targetDate, built.vector) }
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
                    basis = features.first().let { (_, targetDate, built) -> basisOf(built.values, targetDate) },
                    reliability = modelStore.getReliability(detail.canyon.id),
                ).also { predictionCache.save(detail.canyon.id, it) }
            }
        }
    }

    internal companion object {
        /** A list shows estimates computed in the last hours: the weather and reports change slowly. */
        private val ESTIMATE_REFRESH: Duration = Duration.ofHours(3)

        /** The server publishes new estimates about every six hours: the manifest is read again after this. */
        private val SERVER_CHECK_AFTER: Duration = Duration.ofMinutes(90)

        private const val SERVER_WAIT_MS = 5_000L

        /**
         * Open-Meteo counts 400 days of daily values as about 29 calls of its free quota (600 a
         * minute): one whole window every 4 s stays well below it.
         */
        private const val FULL_DOWNLOAD_SPACING_MS = 4_000L

        /** The inputs shown with the estimate of [date], read from its feature values (feature_spec.json names). */
        fun basisOf(values: Map<String, Double?>, date: LocalDate): DebitPredictionBasis {
            fun value(name: String): Double? = values[name]?.takeIf { it.isFinite() }
            val daysSinceLast = value("histDaysSinceLast")?.toLong()
            val lastLevel = value("histLastRank")?.toInt()?.let { ORDINAL_DEBIT_LEVELS.getOrNull(it) }
            return DebitPredictionBasis(
                lastReportDate = daysSinceLast?.takeIf { lastLevel != null }?.let { date.minusDays(it) },
                lastReportLevel = lastLevel.takeIf { daysSinceLast != null },
                reportsLast30Days = value("histCount30d")?.toInt() ?: 0,
                neighborReports = value("nb7Count")?.toInt() ?: 0,
                neighborAnomaly = value("nb7MeanAnom"),
                rainLast3DaysMm = value("wPrecip3d"),
                rainOnDayMm = value("wPrecipDay0"),
                rainGaugeCount = value("mfCount")?.toInt() ?: 0,
                yesterdayGaugeRainMm = value("mfAutoRain1"),
                snowWaterMm = value("hSwe"),
                snowMelt7DaysMm = value("hMelt7d"),
            )
        }
    }
}
