package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.DailyDebitPrediction
import fr.descentecanyon.app.domain.model.DebitPredictionPolicy
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
            )
        }
    }

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
