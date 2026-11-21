package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.DailyDebitPrediction
import fr.descentecanyon.app.domain.model.DebitPredictionBasis
import fr.descentecanyon.app.domain.model.DebitPredictionPolicy
import fr.descentecanyon.app.domain.model.DebitPredictionReliability
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.PredictedDebitLevel
import fr.descentecanyon.app.domain.model.RuntimeLookupSource
import fr.descentecanyon.app.domain.model.WeatherLocationSource
import fr.descentecanyon.app.domain.model.WeatherTarget
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Last flow estimate computed for each canyon, shown offline: the estimate needs fresh weather
 * data, but yesterday's estimate for today is still the best information without network.
 */
@Singleton
class PredictionCache @Inject constructor(private val keyValueDao: KeyValueDao, private val clock: Clock) {

    suspend fun save(canyonId: Int, predictions: CanyonDebitPredictions) {
        // A failed write only costs the offline copy.
        runCatching {
            val entry = CachedPredictions.from(predictions, computedAt = clock.instant())
            keyValueDao.put(
                KeyValueEntity(key(canyonId), json.encodeToString(CachedPredictions.serializer(), entry), updatedAt = clock.millis()),
            )
            keyValueDao.deleteByPrefixOlderThan(KEY_PREFIX, clock.millis() - MAX_AGE.toMillis())
        }
    }

    /** The cached estimate of [canyonId] for today and the following days, if recent enough. */
    suspend fun read(canyonId: Int): CanyonDebitPredictions? {
        val cached = keyValueDao.get(key(canyonId))
            ?.let { payload -> runCatching { json.decodeFromString(CachedPredictions.serializer(), payload) }.getOrNull() }
            ?: return null
        val computedAt = Instant.ofEpochMilli(cached.computedAtEpochMs)
        if (Duration.between(computedAt, clock.instant()) > MAX_AGE) return null
        return cached.toDomain(clock)
    }

    @Serializable
    internal data class CachedPredictions(
        val computedAtEpochMs: Long,
        val targetLatitude: Double,
        val targetLongitude: Double,
        val targetSource: String,
        val timezone: String,
        val fetchedAtEpochMs: Long,
        val lookupSource: String,
        val usedWeatherCache: Boolean,
        val policy: String,
        val ordinalCutpoints: List<Double> = emptyList(),
        val days: List<CachedDailyPrediction> = emptyList(),
        val basis: CachedBasis? = null,
        val reliability: CachedReliability? = null,
    ) {
        fun toDomain(clock: Clock): CanyonDebitPredictions? {
            val zoneId = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("UTC"))
            val today = LocalDate.now(clock.withZone(zoneId))
            val predictions = days.mapNotNull { it.toDomain(today) }.filter { it.horizonDays >= 0 }.sortedBy { it.horizonDays }
            if (predictions.isEmpty()) return null
            return CanyonDebitPredictions(
                target = WeatherTarget(targetLatitude, targetLongitude, enumOr(targetSource, WeatherLocationSource.UNKNOWN)),
                timezone = timezone,
                fetchedAt = Instant.ofEpochMilli(fetchedAtEpochMs),
                lookupSource = enumOr(lookupSource, RuntimeLookupSource.GLOBAL),
                usedWeatherCache = usedWeatherCache,
                policy = enumOr(policy, DebitPredictionPolicy.BALANCED),
                ordinalCutpoints = ordinalCutpoints,
                predictions = predictions,
                computedAt = Instant.ofEpochMilli(computedAtEpochMs),
                isFromCache = true,
                basis = basis?.toDomain(),
                reliability = reliability?.let { DebitPredictionReliability(it.reportCount, it.shownRight, it.firstYear, it.lastYear) },
            )
        }

        companion object {
            fun from(predictions: CanyonDebitPredictions, computedAt: Instant) = CachedPredictions(
                computedAtEpochMs = computedAt.toEpochMilli(),
                targetLatitude = predictions.target.latitude,
                targetLongitude = predictions.target.longitude,
                targetSource = predictions.target.source.name,
                timezone = predictions.timezone,
                fetchedAtEpochMs = predictions.fetchedAt.toEpochMilli(),
                lookupSource = predictions.lookupSource.name,
                usedWeatherCache = predictions.usedWeatherCache,
                policy = predictions.policy.name,
                ordinalCutpoints = predictions.ordinalCutpoints,
                days = predictions.predictions.map(CachedDailyPrediction::from),
                basis = predictions.basis?.let(CachedBasis::from),
                reliability = predictions.reliability?.let { CachedReliability(it.reportCount, it.shownRight, it.firstYear, it.lastYear) },
            )
        }
    }

    @Serializable
    internal data class CachedBasis(
        val lastReportDate: String? = null,
        val lastReportLevel: String? = null,
        val reportsLast30Days: Int = 0,
        val neighborReports: Int = 0,
        val neighborAnomaly: Double? = null,
        val rainLast3DaysMm: Double? = null,
        val rainOnDayMm: Double? = null,
        val rainGaugeCount: Int = 0,
        val yesterdayGaugeRainMm: Double? = null,
        val snowWaterMm: Double? = null,
        val snowMelt7DaysMm: Double? = null,
    ) {
        fun toDomain() = DebitPredictionBasis(
            lastReportDate = lastReportDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            lastReportLevel = lastReportLevel?.let { name -> NiveauDebit.entries.firstOrNull { it.name == name } },
            reportsLast30Days = reportsLast30Days,
            neighborReports = neighborReports,
            neighborAnomaly = neighborAnomaly,
            rainLast3DaysMm = rainLast3DaysMm,
            rainOnDayMm = rainOnDayMm,
            rainGaugeCount = rainGaugeCount,
            yesterdayGaugeRainMm = yesterdayGaugeRainMm,
            snowWaterMm = snowWaterMm,
            snowMelt7DaysMm = snowMelt7DaysMm,
        )

        companion object {
            fun from(basis: DebitPredictionBasis) = CachedBasis(
                lastReportDate = basis.lastReportDate?.toString(),
                lastReportLevel = basis.lastReportLevel?.name,
                reportsLast30Days = basis.reportsLast30Days,
                neighborReports = basis.neighborReports,
                neighborAnomaly = basis.neighborAnomaly,
                rainLast3DaysMm = basis.rainLast3DaysMm,
                rainOnDayMm = basis.rainOnDayMm,
                rainGaugeCount = basis.rainGaugeCount,
                yesterdayGaugeRainMm = basis.yesterdayGaugeRainMm,
                snowWaterMm = basis.snowWaterMm,
                snowMelt7DaysMm = basis.snowMelt7DaysMm,
            )
        }
    }

    @Serializable
    internal data class CachedReliability(val reportCount: Int, val shownRight: Int, val firstYear: Int, val lastYear: Int)

    @Serializable
    internal data class CachedDailyPrediction(
        val date: String,
        val level: String,
        val probabilities: Map<String, Double> = emptyMap(),
        val highThreshold: Double,
        val ordinalScore: Double? = null,
        val ordinalLevel: String? = null,
        val levelProbabilities: Map<String, Double> = emptyMap(),
    ) {
        /** The prediction with its horizon counted from [today]; null when the date is unreadable. */
        fun toDomain(today: LocalDate): DailyDebitPrediction? {
            val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
            return DailyDebitPrediction(
                date = day,
                horizonDays = ChronoUnit.DAYS.between(today, day).toInt(),
                level = enumOr(level, PredictedDebitLevel.MEDIUM),
                probabilities = probabilities.mapNotNull { (name, value) ->
                    PredictedDebitLevel.entries.firstOrNull { it.name == name }?.let { it to value }
                }.toMap(),
                highThreshold = highThreshold,
                ordinalScore = ordinalScore,
                ordinalLevel = ordinalLevel?.let { enumOr(it, NiveauDebit.INCONNU) },
                levelProbabilities = levelProbabilities.mapNotNull { (name, value) ->
                    NiveauDebit.entries.firstOrNull { it.name == name }?.let { it to value }
                }.toMap(),
            )
        }

        companion object {
            fun from(prediction: DailyDebitPrediction) = CachedDailyPrediction(
                date = prediction.date.toString(),
                level = prediction.level.name,
                probabilities = prediction.probabilities.mapKeys { it.key.name },
                highThreshold = prediction.highThreshold,
                ordinalScore = prediction.ordinalScore,
                ordinalLevel = prediction.ordinalLevel?.name,
                levelProbabilities = prediction.levelProbabilities.mapKeys { it.key.name },
            )
        }
    }

    private companion object {
        const val KEY_PREFIX = "prediction."

        /** The estimate covers today and the next two days. */
        val MAX_AGE: Duration = Duration.ofDays(2)

        val json = Json { ignoreUnknownKeys = true }

        fun key(canyonId: Int) = "$KEY_PREFIX$canyonId"
    }
}

private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T = enumValues<T>().firstOrNull { it.name == name } ?: fallback
