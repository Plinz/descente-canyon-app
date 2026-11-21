package fr.descentecanyon.app.domain.model

import java.time.Instant
import java.time.LocalDate

data class DebitPredictionSupport(
    val target: WeatherTarget,
    val timezone: String,
    val fetchedAt: Instant,
    val dailyWeather: List<DailyWeatherValue>,
    val runtimeLookup: ResolvedRuntimeFeatureLookup,
    val usedWeatherCache: Boolean,
    /** Weather model grid point used by Open-Meteo, when known (latitude, longitude, elevation). */
    val weatherGrid: WeatherGridInfo? = null,
    /** Recent reports of every canyon (site day grid), empty when unavailable. */
    val recentCells: List<RecentDebitCell> = emptyList(),
    /** Daily rainfall (mm) of the nearby gauges by station code (daily files: up to today - 2 in France). */
    val stationRain: Map<String, Map<LocalDate, Double>> = emptyMap(),
    /** Yesterday's rainfall (mm, 06-06 UTC) at the nearby automatic gauges, by station code. */
    val automaticRain: Map<String, Double> = emptyMap(),
)

/** One level shown on the site's "derniers débits" grid (rank 0 = SEC .. 5 = CRUE). */
data class RecentDebitCell(val canyonId: Int, val date: LocalDate, val rank: Int)

data class WeatherGridInfo(val latitude: Double, val longitude: Double, val elevation: Double?)

data class DailyWeatherValue(
    val date: LocalDate,
    val precipitationSum: Double? = null,
    val rainSum: Double? = null,
    val snowfallSum: Double? = null,
    val temperature2mMean: Double? = null,
    val temperature2mMin: Double? = null,
    val temperature2mMax: Double? = null,
    val precipitationHours: Double? = null,
    val source: DailyWeatherSource,
)

enum class DailyWeatherSource {
    /** ERA5 reanalysis (same source as the model training data). */
    ARCHIVE,

    /** ECMWF IFS 0.25 deg, used for the last days (ERA5 lags ~5 days) and the forecast. */
    FORECAST,
}

enum class RuntimeLookupSource {
    CANYON,
    MASSIF,
    REGION,
    GLOBAL,
}

data class ResolvedRuntimeFeatureLookup(
    val canyonId: Int,
    val regionKey: String,
    val massifKey: String,
    val lookupSource: RuntimeLookupSource,
    val featureValues: Map<String, Double>,
    /** Canyons within 30 km with their usual (monthly) grid levels. */
    val neighbors: List<NeighborCanyon> = emptyList(),
    /** Météo-France rain gauges within 25 km, closest first. */
    val rainStations: List<RainStation> = emptyList(),
)

data class RainStation(val code: String, val distanceKm: Double)

/** Neighbour canyon: monthly count and level sum of its past grid cells (frozen at export). */
class NeighborCanyon(val canyonId: Int, val distanceKm: Double, val monthlyCounts: DoubleArray, val monthlySums: DoubleArray)

data class DebitRuntimeLookups(
    val schemaVersion: Int,
    val labels: List<String>,
    val lookupFeatureNames: List<String>,
    val defaultRegionKey: String,
    val defaultMassifKey: String,
    val defaults: Map<String, Double>,
    val global: Map<String, Double>,
    val regions: Map<String, Map<String, Double>>,
    val massifs: Map<String, Map<String, Double>>,
    val canyons: Map<Int, RuntimeLookupEntry>,
)

data class RuntimeLookupEntry(val regionKey: String, val massifKey: String, val values: Map<String, Double>)

data class DebitFeatureSpec(
    val schemaVersion: Int,
    val labels: List<String>,
    val features: List<DebitFeatureDefinition>,
    val staticFeatureNames: List<String>,
    val lookupFeatureNames: List<String>,
    val dynamicFeatureNames: List<String>,
)

data class DebitFeatureDefinition(val name: String, val source: DebitFeatureSource, val defaultValue: Double)

enum class DebitFeatureSource {
    COMPUTED,
    STATIC,
    WEATHER,
    LOOKUP,
}

data class CanyonStaticFeatureSet(val canyonId: Int, val featureValues: Map<String, Double>)

enum class DebitPredictionPolicy {
    BALANCED,
    PRUDENT,
    SAFETY_FIRST,
}

enum class PredictedDebitLevel {
    LOW,
    MEDIUM,
    HIGH,
}

data class DebitThresholds(
    val defaultPolicy: DebitPredictionPolicy,
    /** Threshold on P(debit >= GROS) above which the risk bucket is HIGH, per policy. */
    val highThresholdByPolicy: Map<DebitPredictionPolicy, Double>,
    /**
     * Ordinal decision thresholds tau_1..tau_5: the predicted level is the highest k such that
     * P(debit >= k) >= tau_j for every j <= k.
     */
    val cumulativeThresholds: List<Double> = emptyList(),
    /** Display cutpoints of the ordinal gauge (the display score lies in [level - 0.5, level + 0.5)). */
    val ordinalCutpoints: List<Double> = emptyList(),
    val targetMode: String = "six_ordinal_cumulative",
    /** Decision thresholds of the J+1 / J+2 model, when it is shipped. */
    val horizonModel: HorizonModelThresholds? = null,
    /** Neural network averaged with the J0 trees, when it is shipped. */
    val network: DebitNetworkAsset? = null,
)

data class HorizonModelThresholds(
    val modelAsset: String,
    val highThresholdByPolicy: Map<DebitPredictionPolicy, Double>,
    val cumulativeThresholds: List<Double>,
    /** Neural network averaged with the J+1 / J+2 trees, when it is shipped. */
    val network: DebitNetworkAsset? = null,
)

/** Network model file and its share in the probabilities averaged with the trees. */
data class DebitNetworkAsset(val asset: String, val weight: Double)

data class DailyDebitPrediction(
    val date: LocalDate,
    val horizonDays: Int,
    val level: PredictedDebitLevel,
    val probabilities: Map<PredictedDebitLevel, Double>,
    val highThreshold: Double,
    val ordinalScore: Double? = null,
    val ordinalLevel: NiveauDebit? = null,
    /** Probability of each flow level, SEC to CRUE. */
    val levelProbabilities: Map<NiveauDebit, Double> = emptyMap(),
) {
    /**
     * When the model hesitates (the predicted level has less than [UNCERTAIN_LEVEL_PROBABILITY]),
     * the predicted level and its most likely neighbour, lowest first. A range is right more often
     * than a single level in that case (88 % against 84 %, see docs/debit-prediction-model.md).
     */
    fun likelyRange(): DebitLevelRange? {
        val level = ordinalLevel ?: return null
        val index = ORDINAL_DEBIT_LEVELS.indexOf(level)
        val probability = levelProbabilities[level] ?: return null
        if (index < 0 || probability >= UNCERTAIN_LEVEL_PROBABILITY) return null
        val below = ORDINAL_DEBIT_LEVELS.getOrNull(index - 1)
        val above = ORDINAL_DEBIT_LEVELS.getOrNull(index + 1)
        val belowProbability = below?.let { levelProbabilities[it] } ?: 0.0
        val aboveProbability = above?.let { levelProbabilities[it] } ?: 0.0
        return when {
            belowProbability <= 0.0 && aboveProbability <= 0.0 -> null
            aboveProbability >= belowProbability && above != null -> DebitLevelRange(level, above)
            below != null -> DebitLevelRange(below, level)
            else -> null
        }
    }

    /** Probability that the flow is what is shown: the predicted level, or [likelyRange] when there is one. */
    fun shownProbability(): Double? {
        val level = ordinalLevel ?: return null
        val shown = likelyRange()?.let { listOf(it.low, it.high) } ?: listOf(level)
        return shown.map { levelProbabilities[it] ?: return null }.sum()
    }

    /**
     * A flow fit for most groups is likely: what is shown is "correct" (alone, or with its
     * neighbour when the model hesitates) and the high-flow risk is not flagged.
     */
    fun isLikelyNormal(): Boolean {
        if (level == PredictedDebitLevel.HIGH) return false
        val range = likelyRange()
        return if (range != null) NiveauDebit.CORRECT in listOf(range.low, range.high) else ordinalLevel == NiveauDebit.CORRECT
    }

    /**
     * How often what is shown turns out right, from [shownProbability]. The model probabilities are
     * calibrated: in walk-forward evaluation (2023 → Oct. 2026) the shown level or range was right
     * 92 % of the time for HIGH, 77 % for MEDIUM and 64 % for LOW (docs/debit-prediction-model.md).
     */
    fun confidence(): PredictionConfidence? = shownProbability()?.let { probability ->
        when {
            probability >= HIGH_CONFIDENCE_PROBABILITY -> PredictionConfidence.HIGH
            probability >= MEDIUM_CONFIDENCE_PROBABILITY -> PredictionConfidence.MEDIUM
            else -> PredictionConfidence.LOW
        }
    }

    companion object {
        const val UNCERTAIN_LEVEL_PROBABILITY = 0.6
        const val HIGH_CONFIDENCE_PROBABILITY = 0.85
        const val MEDIUM_CONFIDENCE_PROBABILITY = 0.70
    }
}

enum class PredictionConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

/** Two adjacent flow levels, [low] below [high]. */
data class DebitLevelRange(val low: NiveauDebit, val high: NiveauDebit)

/** Flow levels in increasing order, as predicted by the model. */
val ORDINAL_DEBIT_LEVELS: List<NiveauDebit> = listOf(
    NiveauDebit.SEC,
    NiveauDebit.FILET,
    NiveauDebit.CORRECT,
    NiveauDebit.GROS,
    NiveauDebit.TRES_GROS,
    NiveauDebit.CRUE,
)

data class CanyonDebitPredictions(
    val target: WeatherTarget,
    val timezone: String,
    val fetchedAt: Instant,
    val lookupSource: RuntimeLookupSource,
    val usedWeatherCache: Boolean,
    val policy: DebitPredictionPolicy,
    val ordinalCutpoints: List<Double> = emptyList(),
    val predictions: List<DailyDebitPrediction>,
    /** When the estimate was computed (now, unless it comes from the offline copy). */
    val computedAt: Instant = fetchedAt,
    /** True when the network was unavailable and the last estimate computed is shown. */
    val isFromCache: Boolean = false,
    /** What the estimate of the first day was computed from. */
    val basis: DebitPredictionBasis? = null,
    /** How often the estimate was right on this canyon over the test seasons, when it has enough reports. */
    val reliability: DebitPredictionReliability? = null,
) {
    /** The estimate of [date], if it is one of the days computed. */
    fun forDate(date: LocalDate): DailyDebitPrediction? = predictions.firstOrNull { it.date == date }
}

/**
 * The main inputs of an estimate, as the model saw them (reports and gauges known up to yesterday),
 * shown so that the user can judge it: an estimate without recent reports leans on the weather alone.
 */
data class DebitPredictionBasis(
    /** Last report of the canyon taken into account, null when it has none. */
    val lastReportDate: LocalDate? = null,
    val lastReportLevel: NiveauDebit? = null,
    /** Reports of the canyon in the last 30 days. */
    val reportsLast30Days: Int = 0,
    /** Reports of the canyons within 30 km in the last 7 days. */
    val neighborReports: Int = 0,
    /** How far above (positive) or below their usual level these reports are, in levels. */
    val neighborAnomaly: Double? = null,
    /** Rain of the 3 days before and forecast for the day (mm, weather model of the watershed). */
    val rainLast3DaysMm: Double? = null,
    val rainOnDayMm: Double? = null,
    /** Rain gauges within 25 km read (daily network). */
    val rainGaugeCount: Int = 0,
    /** Yesterday's rain at the nearby automatic gauges (mm), when published. */
    val yesterdayGaugeRainMm: Double? = null,
    /** Simulated snowpack of the watershed (mm of water) and what melted in the last 7 days (mm). */
    val snowWaterMm: Double? = null,
    val snowMelt7DaysMm: Double? = null,
) {
    /** No report at all on the canyon: the estimate leans on its massif and neighbours. */
    val usesAreaContext: Boolean get() = lastReportDate == null
}

/** Walk-forward check of the estimate on one canyon: of [reportCount] reports, what was shown was right [shownRight] times. */
data class DebitPredictionReliability(val reportCount: Int, val shownRight: Int, val firstYear: Int, val lastYear: Int) {
    val shownRightFraction: Double get() = if (reportCount == 0) 0.0 else shownRight.toDouble() / reportCount
}
