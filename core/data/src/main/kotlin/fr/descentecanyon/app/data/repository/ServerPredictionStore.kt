package fr.descentecanyon.app.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.remote.backend.LiveCheckDto
import fr.descentecanyon.app.data.remote.backend.ServerCanyonPredictionDto
import fr.descentecanyon.app.data.remote.backend.ServerPredictionsDto
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.DailyDebitPrediction
import fr.descentecanyon.app.domain.model.DebitLiveCheck
import fr.descentecanyon.app.domain.model.DebitLiveCheckHorizon
import fr.descentecanyon.app.domain.model.DebitPredictionPolicy
import fr.descentecanyon.app.domain.model.DebitPredictionReliability
import fr.descentecanyon.app.domain.model.ORDINAL_DEBIT_LEVELS
import fr.descentecanyon.app.domain.model.PredictedDebitLevel
import fr.descentecanyon.app.domain.model.RuntimeLookupSource
import fr.descentecanyon.app.domain.model.WeatherLocationSource
import fr.descentecanyon.app.domain.model.WeatherTarget
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The debit estimates the server computed for every canyon (a few times a day), kept as downloaded
 * (gzipped JSON, checked against the signed manifest) and read once in memory.
 *
 * An estimate is used only while it is the one of today in the canyon's time zone and less than
 * [MAX_AGE] old; otherwise the phone computes it as before.
 */
@Singleton
class ServerPredictionStore internal constructor(private val directory: File, private val json: Json, private val clock: Clock) {
    @Inject
    constructor(@ApplicationContext context: Context, json: Json, clock: Clock) :
        this(File(context.noBackupFilesDir, DIRECTORY_NAME), json, clock)

    private val file: File get() = File(directory, FILE_NAME)
    private val shaFile: File get() = File(directory, SHA_FILE_NAME)

    @Volatile
    private var loaded: Pair<String, ServerPredictionsDto>? = null

    fun installedSha256(): String? = shaFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /** Keeps [gzipped] (already checked against the manifest) as the estimates in use. */
    @Synchronized
    fun install(gzipped: ByteArray, sha256: String) {
        val parsed = decode(gzipped)
        directory.mkdirs()
        val partial = File(directory, "$FILE_NAME.part")
        partial.writeBytes(gzipped)
        if (!partial.renameTo(file)) {
            partial.delete()
            throw IOException("Cannot keep the server estimates")
        }
        shaFile.writeText(sha256)
        loaded = sha256 to parsed
    }

    @Synchronized
    fun clear() {
        loaded = null
        directory.deleteRecursively()
    }

    /** The server's estimate of [canyonId] when it is still today's, null otherwise. */
    fun predictions(canyonId: Int, reliability: DebitPredictionReliability? = null): CanyonDebitPredictions? {
        val estimates = estimates() ?: return null
        val canyon = estimates.canyons[canyonId.toString()] ?: return null
        val predictions = runCatching { toDomain(estimates, canyon, reliability) }.getOrNull() ?: return null
        return predictions.takeIf { isCurrent(it) }
    }

    /** Epoch day of the last report the server knew for [canyonId] when it computed its estimate. */
    fun lastReportDay(canyonId: Int): Long? = estimates()?.canyons?.get(canyonId.toString())?.last

    fun liveCheck(): DebitLiveCheck? = estimates()?.liveCheck?.let(::toDomain)

    private fun isCurrent(predictions: CanyonDebitPredictions): Boolean {
        val now = clock.instant()
        val zone = runCatching { ZoneId.of(predictions.timezone) }.getOrDefault(ZoneId.of("UTC"))
        val first = predictions.predictions.firstOrNull() ?: return false
        return first.date == now.atZone(zone).toLocalDate() && Duration.between(predictions.computedAt, now) < MAX_AGE
    }

    @Synchronized
    private fun estimates(): ServerPredictionsDto? {
        val sha = installedSha256() ?: return null
        loaded?.takeIf { it.first == sha }?.let { return it.second }
        val parsed = runCatching { decode(file.readBytes()) }.getOrNull() ?: return null
        loaded = sha to parsed
        return parsed
    }

    private fun decode(gzipped: ByteArray): ServerPredictionsDto {
        val text = GZIPInputStream(gzipped.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val parsed = json.decodeFromString(ServerPredictionsDto.serializer(), text)
        require(parsed.schema == SCHEMA) { "Unknown estimates schema ${parsed.schema}" }
        return parsed
    }

    internal companion object {
        const val SCHEMA = 1
        private const val DIRECTORY_NAME = "server-predictions"
        private const val FILE_NAME = "predictions.json.gz"
        private const val SHA_FILE_NAME = "predictions.sha256"
        private const val HIGH_RANK = 3
        private const val LOW_RANK = 1

        /** The server runs three times a day: an estimate older than this one missed two runs. */
        val MAX_AGE: Duration = Duration.ofHours(14)

        internal fun toDomain(
            file: ServerPredictionsDto,
            canyon: ServerCanyonPredictionDto,
            reliability: DebitPredictionReliability?,
        ): CanyonDebitPredictions {
            val computedAt = Instant.ofEpochSecond(file.computedAt)
            val date = canyon.days.firstOrNull()?.get(0)?.jsonPrimitive?.content?.let(LocalDate::parse)
            return CanyonDebitPredictions(
                target = WeatherTarget(
                    latitude = canyon.lat,
                    longitude = canyon.lon,
                    source = enumValueOrNull<WeatherLocationSource>(canyon.src) ?: WeatherLocationSource.UNKNOWN,
                ),
                timezone = canyon.tz,
                fetchedAt = Instant.ofEpochSecond(canyon.wx),
                lookupSource = enumValueOrNull<RuntimeLookupSource>(canyon.lookup) ?: RuntimeLookupSource.GLOBAL,
                usedWeatherCache = false,
                policy = enumValueOrNull<DebitPredictionPolicy>(file.policy) ?: DebitPredictionPolicy.BALANCED,
                ordinalCutpoints = file.ordinalCutpoints,
                predictions = canyon.days.map(::dailyPrediction),
                computedAt = computedAt,
                basis = date?.let { DebitPredictionRepositoryImpl.basisOf(canyon.basis, it) },
                reliability = reliability,
                computedOnServer = true,
            )
        }

        /** `[date, horizonDays, [p SEC..CRUE], rank, displayScore, highThreshold]`, decided as DebitModelPredictor does. */
        private fun dailyPrediction(day: kotlinx.serialization.json.JsonArray): DailyDebitPrediction {
            val distribution = day[2].jsonArray.map { it.jsonPrimitive.double }
            require(distribution.size == ORDINAL_DEBIT_LEVELS.size) { "Expected ${ORDINAL_DEBIT_LEVELS.size} probabilities" }
            val rank = day[3].jsonPrimitive.int.coerceIn(0, ORDINAL_DEBIT_LEVELS.lastIndex)
            val highThreshold = day[5].jsonPrimitive.double
            val highProbability = distribution.drop(HIGH_RANK).sum()
            val level = when {
                rank >= HIGH_RANK || highProbability >= highThreshold -> PredictedDebitLevel.HIGH
                rank <= LOW_RANK -> PredictedDebitLevel.LOW
                else -> PredictedDebitLevel.MEDIUM
            }
            return DailyDebitPrediction(
                date = LocalDate.parse(day[0].jsonPrimitive.content),
                horizonDays = day[1].jsonPrimitive.int,
                level = level,
                probabilities = mapOf(
                    PredictedDebitLevel.LOW to distribution[0] + distribution[1],
                    PredictedDebitLevel.MEDIUM to distribution[2],
                    PredictedDebitLevel.HIGH to highProbability,
                ),
                highThreshold = highThreshold,
                ordinalScore = day[4].jsonPrimitive.double,
                ordinalLevel = ORDINAL_DEBIT_LEVELS[rank],
                levelProbabilities = ORDINAL_DEBIT_LEVELS.withIndex().associate { (index, value) -> value to distribution[index] },
            )
        }

        private fun toDomain(check: LiveCheckDto): DebitLiveCheck? {
            val since = runCatching { LocalDate.parse(check.since) }.getOrNull() ?: return null
            return DebitLiveCheck(
                since = since,
                days = check.days,
                horizons = check.byHorizon.sortedBy { it.horizon }.map {
                    DebitLiveCheckHorizon(
                        horizonDays = it.horizon,
                        reportCount = it.reports,
                        exactCount = it.exact,
                        withinOneCount = it.withinOne,
                        highReportCount = it.highReports,
                        highCaughtCount = it.highCaught,
                    )
                },
            )
        }

        private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? = enumValues<T>().firstOrNull { it.name == name }
    }
}
