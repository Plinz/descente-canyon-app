package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonTimeZone
import fr.descentecanyon.app.domain.model.DailyDebitPrediction
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.PredictedDebitLevel
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitPredictionRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The day of [outing] became a high-flow risk at [canyonName] (one of its canyons). */
data class OutingFlowAlert(
    val outing: PlannedOuting,
    val canyonId: Int,
    val canyonName: String,
    /** Days from today to the outing: 0, 1 or 2. */
    val horizonDays: Int,
    val prediction: DailyDebitPrediction,
)

/**
 * Keeps the flow estimates of the planned outings fresh, in the background: the evening before and
 * the morning of an outing, the estimate of each of its canyons is computed again with the reports
 * read again from the site, and saved (the plan shows it offline, in the mountains). When the day of
 * an outing becomes a high-flow risk, it is told once; the risk falling back re-arms the alert.
 */
@Singleton
class OutingEstimateMonitor @Inject constructor(
    private val outingRepository: OutingRepository,
    private val canyonRepository: CanyonRepository,
    private val debitRepository: DebitRepository,
    private val predictionRepository: DebitPredictionRepository,
    private val keyValueDao: KeyValueDao,
    private val connectivityObserver: ConnectivityObserver,
    private val clock: Clock,
) {

    /** True while an outing is planned and not started: its day will come within the days estimated. */
    fun isWatched(outings: List<PlannedOuting>): Boolean {
        val now = clock.instant()
        return outings.any { it.start.isAfter(now) }
    }

    /** Computes the estimates again (online) and returns the outings whose day just became a high-flow risk. */
    suspend fun refresh(): List<OutingFlowAlert> {
        if (!connectivityObserver.isCurrentlyOnline()) return emptyList()
        val now = clock.instant()
        val alerts = mutableListOf<OutingFlowAlert>()
        val outings = outingRepository.observeAll().first().filter { it.start.isAfter(now) }
        val alerted = readState()
        val kept = HashMap<Long, StoredAlerts>()
        for (outing in outings) {
            val flagged = mutableSetOf<Int>()
            var day: LocalDate? = null
            for (canyonId in outing.canyonIds.distinct()) {
                val detail = canyonRepository.getCanyonPreview(canyonId).getOrNull()?.takeIf { it.isLocated } ?: continue
                val zone = detail.zone()
                val outingDay = outing.dayIn(zone)
                val horizon = ChronoUnit.DAYS.between(LocalDate.now(clock.withZone(zone)), outingDay).toInt()
                if (horizon !in 0..LAST_ESTIMATED_DAY) continue
                day = outingDay
                // The reports of the last hours are the best input: read again, unless just read.
                val reports = debitRepository.refreshDebits(canyonId, REPORTS_MAX_AGE_MS).getOrNull()
                val prediction = predictionRepository.getPredictions(reports?.let { detail.copy(debits = it) } ?: detail)
                    .getOrNull()
                    ?.forDate(outingDay)
                    ?: continue
                if (prediction.level != PredictedDebitLevel.HIGH) continue
                flagged += canyonId
                val already = alerted[outing.id]?.takeIf { it.day == outingDay.toString() }?.canyonIds.orEmpty()
                if (canyonId !in already) alerts += OutingFlowAlert(outing, canyonId, detail.canyon.nom, horizon, prediction)
            }
            // Only what is still flagged stays alerted: a risk that falls back is told again if it returns.
            val already = alerted[outing.id]?.takeIf { it.day == day?.toString() }?.canyonIds.orEmpty()
            if (day != null) kept[outing.id] = StoredAlerts(day.toString(), already.intersect(flagged).toList())
        }
        writeState(kept)
        return alerts
    }

    /** [alert] was shown: it is not repeated while the risk stays. */
    suspend fun markAlerted(alert: OutingFlowAlert) {
        val state = readState().toMutableMap()
        val day = alert.prediction.date.toString()
        val current = state[alert.outing.id]?.takeIf { it.day == day }
        state[alert.outing.id] = StoredAlerts(day, (current?.canyonIds.orEmpty() + alert.canyonId).distinct())
        writeState(state)
    }

    private fun CanyonDetail.zone(): ZoneId {
        val point = geoPoints.firstOrNull { it.type == GeoPointType.PARKING_AMONT } ?: geoPoints.firstOrNull()
        return CanyonTimeZone.of(canyon.pays, point?.latitude, point?.longitude) ?: clock.zone
    }

    private suspend fun readState(): Map<Long, StoredAlerts> = keyValueDao.get(STATE_KEY)
        ?.let { payload -> runCatching { json.decodeFromString(StoredState.serializer(), payload) }.getOrNull() }
        ?.outings
        ?.mapKeys { (id, _) -> id.toLong() }
        .orEmpty()

    private suspend fun writeState(state: Map<Long, StoredAlerts>) {
        val payload = json.encodeToString(StoredState.serializer(), StoredState(state.mapKeys { (id, _) -> id.toString() }))
        keyValueDao.put(KeyValueEntity(STATE_KEY, payload, updatedAt = clock.millis()))
    }

    @Serializable
    private data class StoredState(val outings: Map<String, StoredAlerts> = emptyMap())

    /** The canyons already told for the outing [day]. */
    @Serializable
    private data class StoredAlerts(val day: String, val canyonIds: List<Int> = emptyList())

    private companion object {
        const val STATE_KEY = "outing.flowAlerts"

        /** The model estimates today and the next two days. */
        const val LAST_ESTIMATED_DAY = 2

        /** Reports read less than half an hour ago are not read again. */
        const val REPORTS_MAX_AGE_MS = 30L * 60L * 1000L

        val json = Json { ignoreUnknownKeys = true }
    }
}
