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
data class RecentDebitCell(
    val canyonId: Int,
    val date: LocalDate,
    val rank: Int,
)

data class WeatherGridInfo(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double?,
)

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

data class RainStation(
    val code: String,
    val distanceKm: Double,
)

/** Neighbour canyon: monthly count and level sum of its past grid cells (frozen at export). */
class NeighborCanyon(
    val canyonId: Int,
    val distanceKm: Double,
    val monthlyCounts: DoubleArray,
    val monthlySums: DoubleArray,
)

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

data class RuntimeLookupEntry(
    val regionKey: String,
    val massifKey: String,
    val values: Map<String, Double>,
)

data class DebitFeatureSpec(
    val schemaVersion: Int,
    val labels: List<String>,
    val features: List<DebitFeatureDefinition>,
    val staticFeatureNames: List<String>,
    val lookupFeatureNames: List<String>,
    val dynamicFeatureNames: List<String>,
)

data class DebitFeatureDefinition(
    val name: String,
    val source: DebitFeatureSource,
    val defaultValue: Double,
)

enum class DebitFeatureSource {
    COMPUTED,
    STATIC,
    WEATHER,
    LOOKUP,
}

data class CanyonStaticFeatureSet(
    val canyonId: Int,
    val featureValues: Map<String, Double>,
)

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
)

data class HorizonModelThresholds(
    val modelAsset: String,
    val highThresholdByPolicy: Map<DebitPredictionPolicy, Double>,
    val cumulativeThresholds: List<Double>,
)

data class DailyDebitPrediction(
    val date: LocalDate,
    val horizonDays: Int,
    val level: PredictedDebitLevel,
    val probabilities: Map<PredictedDebitLevel, Double>,
    val highThreshold: Double,
    val ordinalScore: Double? = null,
    val ordinalLevel: NiveauDebit? = null,
    val ordinalStandardDeviation: Double? = null,
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
)
