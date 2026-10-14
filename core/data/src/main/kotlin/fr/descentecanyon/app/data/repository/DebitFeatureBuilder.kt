package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonStaticFeatureSet
import fr.descentecanyon.app.domain.model.DailyWeatherValue
import fr.descentecanyon.app.domain.model.DebitFeatureSpec
import fr.descentecanyon.app.domain.model.DebitPredictionSupport
import fr.descentecanyon.app.domain.model.NeighborCanyon
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.RainStation
import fr.descentecanyon.app.domain.model.RecentDebitCell
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

/**
 * Builds the v3 debit model feature vector. Port of `scripts/debit_model/features.py`: every
 * feature must be computed exactly like the Python reference (checked by golden vectors).
 */
@Singleton
class DebitFeatureBuilder @Inject constructor() {

    /** Returns null when the weather window around [targetDate] is too incomplete to predict. */
    fun buildFeatureVector(
        detail: CanyonDetail,
        support: DebitPredictionSupport,
        featureSpec: DebitFeatureSpec,
        staticFeatureSet: CanyonStaticFeatureSet?,
        targetDate: LocalDate,
        horizonDays: Int = 0,
    ): FloatArray? {
        val history = detail.debits
            .mapNotNull { debit ->
                val rank = debit.niveau.ordinalRank() ?: return@mapNotNull null
                if (DebitReportQuality.isMessageOnlyHighReport(rank, debit.isDescended, debit.commentaire)) return@mapNotNull null
                debit.date.toEpochDay() to rank
            }
            .ifEmpty { decodeLookupHistory(support.runtimeLookup.featureValues) }
        val input = DebitFeatureInput(
            targetDate = targetDate,
            weather = support.dailyWeather,
            gridLatitude = support.weatherGrid?.latitude,
            gridLongitude = support.weatherGrid?.longitude,
            gridElevation = support.weatherGrid?.elevation,
            staticValues = staticFeatureSet?.featureValues.orEmpty(),
            altitudeDepart = detail.canyon.altitudeDepart?.toDouble(),
            denivele = detail.canyon.denivele?.toDouble(),
            longueur = detail.canyon.longueur?.toDouble(),
            cascadeMax = detail.canyon.cascadeMax?.toDouble(),
            history = history,
            massif = MonthlyAggregate.fromLookup(support.runtimeLookup.featureValues, MASSIF_PREFIX),
            region = MonthlyAggregate.fromLookup(support.runtimeLookup.featureValues, REGION_PREFIX),
            global = MonthlyAggregate.fromLookup(support.runtimeLookup.featureValues, GLOBAL_PREFIX),
            neighbors = support.runtimeLookup.neighbors,
            recentCells = support.recentCells,
            rainStations = support.runtimeLookup.rainStations,
            stationRain = support.stationRain,
            automaticRain = support.automaticRain,
            horizonDays = horizonDays,
        )
        val values = computeFeatureValues(input) ?: return null
        return featureSpec.features.map { feature ->
            val value = values[feature.name]
            (if (value == null || value.isNaN() || value.isInfinite()) feature.defaultValue else value).toFloat()
        }.toFloatArray()
    }

    data class DebitFeatureInput(
        val targetDate: LocalDate,
        val weather: List<DailyWeatherValue>,
        val gridLatitude: Double?,
        val gridLongitude: Double?,
        val gridElevation: Double?,
        val staticValues: Map<String, Double>,
        val altitudeDepart: Double?,
        val denivele: Double?,
        val longueur: Double?,
        val cascadeMax: Double?,
        /** (epochDay, rank) of the canyon's reports, any order, may include the target day or later. */
        val history: List<Pair<Long, Int>>,
        val massif: MonthlyAggregate,
        val region: MonthlyAggregate,
        val global: MonthlyAggregate,
        val neighbors: List<NeighborCanyon> = emptyList(),
        val recentCells: List<RecentDebitCell> = emptyList(),
        val rainStations: List<RainStation> = emptyList(),
        val stationRain: Map<String, Map<LocalDate, Double>> = emptyMap(),
        /** Yesterday's rain at the automatic gauges (today - 1 = J - horizonDays - 1). */
        val automaticRain: Map<String, Double> = emptyMap(),
        /**
         * Days between today and [targetDate]. Reports and gauges are only known up to today - 1,
         * so their windows end `horizonDays` days before J - 1 (same as the training augmentation).
         */
        val horizonDays: Int = 0,
    )

    fun computeFeatureValues(input: DebitFeatureInput): Map<String, Double?>? {
        val static = staticFeatures(input)
        val weather = weatherFeatures(input, static) ?: return null
        val targetDay = input.targetDate.toEpochDay()
        val past = input.history.filter { (day, _) -> day < targetDay }
        val priors = areaPriors(input.targetDate.monthValue, input.massif, input.region, input.global)
        return buildMap {
            putAll(timeFeatures(input.targetDate))
            putAll(static)
            putAll(weather)
            putAll(historyFeatures(past, targetDay, input.targetDate.monthValue, priors))
            putAll(neighborFeatures(targetDay, input.neighbors, input.recentCells, input.horizonDays))
            // Daily gauge files stop at today - 2: daily features as of J - horizon - 1.
            putAll(rainFeatures(input.targetDate.minusDays(input.horizonDays + 1L), input.rainStations, input.stationRain))
            putAll(automaticRainFeatures(input.rainStations, input.automaticRain))
            put("infoLagDays", input.horizonDays.toDouble())
        }
    }

    /** Port of meteofrance.auto_features_from_values: yesterday's rain at the automatic gauges. */
    private fun automaticRainFeatures(stations: List<RainStation>, automaticRain: Map<String, Double>): Map<String, Double?> {
        val found = stations.mapNotNull { station -> automaticRain[station.code]?.let { station.distanceKm to it } }
        val features = HashMap<String, Double?>()
        features["mfAutoCount"] = found.size.toDouble()
        if (found.isEmpty()) return features
        val weights = found.map { (distance, _) -> 1.0 / (1.0 + distance / 5.0) }
        features["mfAutoRain1"] = found.indices.sumOf { weights[it] * found[it].second } / weights.sum()
        features["mfAutoMax1"] = found.maxOf { it.second }
        features["mfAutoNearestKm"] = found.minOf { it.first }
        return features
    }

    /** Port of meteofrance.features_v2: 14/30-day sums and wet days at the nearest usable gauge. */
    private fun longRainFeatures(
        targetDate: LocalDate,
        stations: List<RainStation>,
        stationRain: Map<String, Map<LocalDate, Double>>,
    ): Map<String, Double?> {
        for (station in stations) {
            val byDay = stationRain[station.code] ?: continue
            val rain = (1..30).map { byDay[targetDate.minusDays(it.toLong())] }
            if (rain[0] == null || rain.count { it == null } > 4) continue
            val filled = rain.map { it ?: 0.0 }
            return mapOf(
                "mfRain14" to filled.take(14).sum(),
                "mfRain30" to filled.sum(),
                "mfWetDays7" to filled.take(7).count { it >= 1.0 }.toDouble(),
            )
        }
        return emptyMap()
    }

    /** Port of meteofrance.features_for: Météo-France gauges within 25 km, RR up to J-1. */
    private fun rainFeatures(
        targetDate: LocalDate,
        stations: List<RainStation>,
        stationRain: Map<String, Map<LocalDate, Double>>,
    ): Map<String, Double?> {
        val usable = mutableListOf<DoubleArray>() // distance, rain J-1, sum 3 days, sum 7 days
        for (station in stations) {
            val byDay = stationRain[station.code] ?: continue
            val rain = (1..7).map { byDay[targetDate.minusDays(it.toLong())] }
            if (rain[0] == null || rain.count { it == null } > 2) continue
            val filled = rain.map { it ?: 0.0 }
            usable += doubleArrayOf(station.distanceKm, filled[0], filled.take(3).sum(), filled.sum())
        }
        val features = HashMap<String, Double?>()
        features.putAll(longRainFeatures(targetDate, stations, stationRain))
        features["mfCount"] = usable.size.toDouble()
        if (usable.isEmpty()) return features
        val weights = usable.map { 1.0 / (1.0 + it[0] / 5.0) }
        val weightSum = weights.sum()
        features["mfRain1"] = usable.indices.sumOf { weights[it] * usable[it][1] } / weightSum
        features["mfRain3"] = usable.indices.sumOf { weights[it] * usable[it][2] } / weightSum
        features["mfRain7"] = usable.indices.sumOf { weights[it] * usable[it][3] } / weightSum
        features["mfMax1"] = usable.maxOf { it[1] }
        features["mfMax3"] = usable.maxOf { it[2] }
        features["mfNearestKm"] = usable.minOf { it[0] }
        val close = usable.filter { it[0] <= RAIN_CLOSE_KM }
        if (close.isNotEmpty()) {
            features["mfClose1"] = close.sumOf { it[1] } / close.size
            features["mfClose3"] = close.sumOf { it[2] } / close.size
        }
        return features
    }

    /** Port of neighbors.features_for: recent grid levels of canyons within 30 km. */
    private fun neighborFeatures(
        targetDay: Long,
        neighbors: List<NeighborCanyon>,
        recentCells: List<RecentDebitCell>,
        lagDays: Int,
    ): Map<String, Double?> {
        val lastKnownDay = targetDay - 1 - lagDays
        val cellsByCanyon = recentCells.groupBy { it.canyonId }.mapValues { (_, cells) ->
            cells.groupBy { it.date.toEpochDay() }.map { (day, sameDay) -> day to sameDay.minOf { it.rank } }.sortedBy { it.first }
        }
        val window3 = mutableListOf<NeighborCell>()
        val window7 = mutableListOf<NeighborCell>()
        for (neighbor in neighbors) {
            val cells = cellsByCanyon[neighbor.canyonId] ?: continue
            for ((day, level) in cells) {
                if (day < targetDay - 7 || day > lastKnownDay) continue
                val month = LocalDate.ofEpochDay(day).monthValue - 1
                var weight = 0.0
                var total = 0.0
                for ((offset, w) in SEASON_WEIGHTS) {
                    val index = Math.floorMod(month + offset, 12)
                    weight += w * neighbor.monthlyCounts[index]
                    total += w * neighbor.monthlySums[index]
                }
                val usual = (total + NEIGHBOR_PRIOR_STRENGTH * NEIGHBOR_PRIOR_MEAN) / (weight + NEIGHBOR_PRIOR_STRENGTH)
                val cell = NeighborCell(level.toDouble(), level - usual, 1.0 / (1.0 + neighbor.distanceKm / 10.0), neighbor.distanceKm)
                window7 += cell
                if (day >= targetDay - 3) window3 += cell
            }
        }
        val features = HashMap<String, Double?>()
        for ((window, cells) in listOf(3 to window3, 7 to window7)) {
            features["nb${window}Count"] = cells.size.toDouble()
            if (cells.isEmpty()) continue
            val weightSum = cells.sumOf { it.weight }
            features["nb${window}MeanAnom"] = cells.sumOf { it.weight * it.anomaly } / weightSum
            features["nb${window}FracHigh"] = cells.sumOf { if (it.level >= HIGH_RANK) it.weight else 0.0 } / weightSum
            if (window == 3) {
                features["nb3MaxAnom"] = cells.maxOf { it.anomaly }
                features["nb3MeanLevel"] = cells.sumOf { it.weight * it.level } / weightSum
                val close = cells.filter { it.distanceKm <= NEIGHBOR_CLOSE_KM }
                features["nbClose3MeanAnom"] = if (close.isEmpty()) null else close.sumOf { it.anomaly } / close.size
                features["nbNearest3Km"] = cells.minOf { it.distanceKm }
            }
        }
        return features
    }

    private class NeighborCell(val level: Double, val anomaly: Double, val weight: Double, val distanceKm: Double)

    private fun timeFeatures(targetDate: LocalDate): Map<String, Double> {
        val angle = 2.0 * PI * (targetDate.dayOfYear - 1) / 365.25
        return mapOf("doySin" to roundTo(sin(angle), 6), "doyCos" to roundTo(cos(angle), 6))
    }

    private fun staticFeatures(input: DebitFeatureInput): Map<String, Double?> {
        val values = HashMap<String, Double?>(input.staticValues)
        if (!values.containsKey("altitudeDepartM")) values["altitudeDepartM"] = input.altitudeDepart
        if (!values.containsKey("deniveleM")) values["deniveleM"] = input.denivele
        if (!values.containsKey("longueurM")) values["longueurM"] = input.longueur
        if (!values.containsKey("cascadeMaxM")) values["cascadeMaxM"] = input.cascadeMax
        input.gridLatitude?.let { values["weatherLatitude"] = it }
        input.gridLongitude?.let { values["weatherLongitude"] = it }
        input.gridElevation?.let { values["weatherElevationM"] = it }
        if (BAND_KEYS.any { values[it] == null }) {
            // Same fallback as hydro.basin_parameters for canyons without hypsometry.
            val base = values["altitudeDepartM"] ?: values["weatherElevationM"] ?: 1000.0
            FALLBACK_BAND_OFFSETS_M.forEachIndexed { index, offset -> values[BAND_KEYS[index]] = roundTo(base + offset, 1) }
        }
        if (values["hydroSoilCapacityMm"] == null) values["hydroSoilCapacityMm"] = DEFAULT_SOIL_CAPACITY_MM
        return values
    }

    private fun weatherFeatures(input: DebitFeatureInput, static: Map<String, Double?>): Map<String, Double?>? {
        val window = DebitHydroModel.WINDOW_DAYS
        val byDate = input.weather.associateBy { it.date }
        val firstDate = input.targetDate.minusDays((window - 1).toLong())
        val precipitationRaw = arrayOfNulls<Double>(window)
        val temperatureRaw = arrayOfNulls<Double>(window)
        val dayOfYear = IntArray(window)
        var validDays = 0
        for (index in 0 until window) {
            val date = firstDate.plusDays(index.toLong())
            val row = byDate[date]
            precipitationRaw[index] = row?.precipitationSum
            temperatureRaw[index] = row?.temperature2mMean
            dayOfYear[index] = date.dayOfYear
            if (row?.precipitationSum != null && row.temperature2mMean != null) validDays++
        }
        if (validDays < DebitHydroModel.MIN_VALID_DAYS || precipitationRaw[window - 1] == null) return null

        val precipitation = DoubleArray(window) { precipitationRaw[it] ?: 0.0 }
        val temperature = fillTemperature(temperatureRaw)
        val latitude = input.gridLatitude ?: static["weatherLatitude"] ?: return null
        val referenceElevation = input.gridElevation ?: static["weatherElevationM"] ?: static["altitudeDepartM"] ?: return null
        val bands = DoubleArray(DebitHydroModel.BAND_COUNT) { static.getValue(BAND_KEYS[it])!! }
        val soilCapacity = static["hydroSoilCapacityMm"] ?: DEFAULT_SOIL_CAPACITY_MM

        val targetDay = input.targetDate.toEpochDay()
        val lastDay = input.history.filter { it.first < targetDay }.maxOfOrNull { it.first }
        val since = lastDay?.let { targetDay - it }?.takeIf { it in 1..DELTA_MAX_DAYS }
        val deltaOk = since != null
        val captureIndex = since?.let { (window - 1) - it.toInt() } ?: -1

        val result = DebitHydroModel.simulate(
            precipitation = precipitation,
            temperature = temperature,
            dayOfYear = dayOfYear,
            latitudeDeg = latitude,
            referenceElevationM = referenceElevation,
            bandElevationsM = bands,
            soilCapacityMm = soilCapacity,
            captureIndex = captureIndex,
        )
        val last = window - 1
        val features = HashMap<String, Double?>()
        features["wPrecipDay0"] = precipitation[last]
        features["wPrecipDay1"] = precipitation[last - 1]
        features["wPrecip3d"] = sumRange(precipitation, last - 3, last)
        features["wPrecip7d"] = sumRange(precipitation, last - 7, last)
        features["wPrecip30d"] = sumRange(precipitation, last - 30, last)
        features["wPrecipMax7d"] = (last - 7 until last).maxOf { precipitation[it] }
        features["wTempDay0"] = temperature[last]
        features["wTemp7d"] = sumRange(temperature, last - 7, last) / 7.0
        features["hLiquidDay0"] = result.liquidInput[last]
        features["hLiquid3d"] = sumRange(result.liquidInput, last - 3, last)
        features["hLiquid7d"] = sumRange(result.liquidInput, last - 7, last)
        features["hMelt7d"] = sumRange(result.melt, last - 7, last)
        features["hSwe"] = result.morningSwe
        features["hSnowCover"] = result.morningSnowCover
        features["hSweChange30d"] = result.swe[last - 1] - result.swe[last - 31]
        features["hSoilFill"] = result.morningSoilFill
        RESERVOIR_NAMES.forEachIndexed { index, name ->
            features["h$name"] = result.morningQ[index]
            features["hZ$name"] = zScore(ln1p(result.morningQ[index]), static, name)
        }
        features["hQ1Evening"] = result.eveningQ[0]
        features["hQ3Evening"] = result.eveningQ[1]
        features["hZQ1Evening"] = zScore(ln1p(result.eveningQ[0]), static, "Q1")
        val captured = result.capturedQ
        if (deltaOk && captured != null) {
            features["hDeltaLogQ3"] = ln1p(result.morningQ[1]) - ln1p(captured[1])
            features["hDeltaLogQ10"] = ln1p(result.morningQ[2]) - ln1p(captured[2])
            features["hDeltaLogQ30"] = ln1p(result.morningQ[3]) - ln1p(captured[3])
            features["hLiquidSinceLast"] = sumRange(result.liquidInput, captureIndex, last)
            features["hSweChangeSinceLast"] = result.morningSwe - (result.capturedSwe ?: 0.0)
        }
        return features
    }

    private fun zScore(logQ: Double, static: Map<String, Double?>, name: String): Double? {
        val mean = static["hydroLog${name}Mean"] ?: return null
        val std = static["hydroLog${name}Std"] ?: return null
        return (logQ - mean) / std
    }

    data class AreaPriors(
        val regionSeasonMeanRank: Double,
        val regionSeasonFracHigh: Double,
        val regionSeasonFracLow: Double,
        val massifSeasonMeanRank: Double,
        val massifSeasonFracHigh: Double,
        val massifSeasonFracLow: Double,
        val massifSeasonWeightLog: Double,
    )

    private fun areaPriors(month: Int, massif: MonthlyAggregate, region: MonthlyAggregate, global: MonthlyAggregate): AreaPriors {
        val g = global.season(month)
        val gCount = max(g[0], 1e-9)
        val globalMean = g[1] / gCount
        val globalHigh = g[2] / gCount
        val globalLow = g[3] / gCount
        val r = region.season(month)
        val regionMean = smoothed(r[1], r[0], globalMean, REGION_PRIOR_STRENGTH)
        val regionHigh = smoothed(r[2], r[0], globalHigh, REGION_PRIOR_STRENGTH)
        val regionLow = smoothed(r[3], r[0], globalLow, REGION_PRIOR_STRENGTH)
        val m = massif.season(month)
        return AreaPriors(
            regionSeasonMeanRank = regionMean,
            regionSeasonFracHigh = regionHigh,
            regionSeasonFracLow = regionLow,
            massifSeasonMeanRank = smoothed(m[1], m[0], regionMean, MASSIF_PRIOR_STRENGTH),
            massifSeasonFracHigh = smoothed(m[2], m[0], regionHigh, MASSIF_PRIOR_STRENGTH),
            massifSeasonFracLow = smoothed(m[3], m[0], regionLow, MASSIF_PRIOR_STRENGTH),
            massifSeasonWeightLog = ln1p(m[0]),
        )
    }

    private fun historyFeatures(past: List<Pair<Long, Int>>, targetDay: Long, month: Int, priors: AreaPriors): Map<String, Double?> {
        val canyon = MonthlyAggregate()
        var count7 = 0
        var count30 = 0
        var count365 = 0
        var rank7 = 0.0
        var rank30 = 0.0
        var lastDay: Long? = null
        var lastRank: Int? = null
        var totalRank = 0.0
        for ((day, rank) in past) {
            canyon.add(LocalDate.ofEpochDay(day).monthValue, rank)
            totalRank += rank
            val age = targetDay - day
            if (age <= 7) {
                count7++
                rank7 += rank
            }
            if (age <= 30) {
                count30++
                rank30 += rank
            }
            if (age <= 365) count365++
            if (lastDay == null || day > lastDay || (day == lastDay && rank > (lastRank ?: 0))) {
                lastDay = day
                lastRank = rank
            }
        }
        val season = canyon.season(month)
        val seasonMean = smoothed(season[1], season[0], priors.massifSeasonMeanRank, CANYON_PRIOR_STRENGTH)
        val count = past.size.toDouble()
        return mapOf(
            "histCount" to count,
            "histLastRank" to lastRank?.toDouble(),
            "histDaysSinceLast" to lastDay?.let { (targetDay - it).toDouble() },
            "histCount7d" to count7.toDouble(),
            "histMeanRank7d" to if (count7 > 0) rank7 / count7 else null,
            "histCount30d" to count30.toDouble(),
            "histMeanRank30d" to if (count30 > 0) rank30 / count30 else null,
            "histCount365d" to count365.toDouble(),
            "histSeasonWeight" to season[0],
            "histSeasonMeanRank" to seasonMean,
            "histSeasonFracHigh" to smoothed(season[2], season[0], priors.massifSeasonFracHigh, CANYON_PRIOR_STRENGTH),
            "histSeasonFracLow" to smoothed(season[3], season[0], priors.massifSeasonFracLow, CANYON_PRIOR_STRENGTH),
            "histMeanRank" to smoothed(totalRank, count, priors.massifSeasonMeanRank, CANYON_PRIOR_STRENGTH),
            "histLastAnomaly" to lastRank?.let { it - seasonMean },
            "massifSeasonMeanRank" to priors.massifSeasonMeanRank,
            "massifSeasonFracHigh" to priors.massifSeasonFracHigh,
            "massifSeasonWeightLog" to priors.massifSeasonWeightLog,
            "regionSeasonMeanRank" to priors.regionSeasonMeanRank,
            "regionSeasonFracHigh" to priors.regionSeasonFracHigh,
        )
    }

    /** Per-month report counts (port of features.MonthlyAggregate). */
    class MonthlyAggregate(
        val count: DoubleArray = DoubleArray(12),
        val rankSum: DoubleArray = DoubleArray(12),
        val high: DoubleArray = DoubleArray(12),
        val low: DoubleArray = DoubleArray(12),
    ) {
        fun add(month: Int, rank: Int) {
            val index = month - 1
            count[index] += 1.0
            rankSum[index] += rank.toDouble()
            if (rank >= HIGH_RANK) high[index] += 1.0
            if (rank <= LOW_RANK) low[index] += 1.0
        }

        /** Weighted (count, rankSum, high, low) over the month and its neighbours. */
        fun season(month: Int): DoubleArray {
            val totals = DoubleArray(4)
            for ((offset, weight) in SEASON_WEIGHTS) {
                val index = Math.floorMod(month - 1 + offset, 12)
                totals[0] += weight * count[index]
                totals[1] += weight * rankSum[index]
                totals[2] += weight * high[index]
                totals[3] += weight * low[index]
            }
            return totals
        }

        companion object {
            fun fromLookup(values: Map<String, Double>, prefix: String): MonthlyAggregate {
                val aggregate = MonthlyAggregate()
                for (month in 1..12) {
                    aggregate.count[month - 1] = values["$prefix.m$month.count"] ?: 0.0
                    aggregate.rankSum[month - 1] = values["$prefix.m$month.rankSum"] ?: 0.0
                    aggregate.high[month - 1] = values["$prefix.m$month.high"] ?: 0.0
                    aggregate.low[month - 1] = values["$prefix.m$month.low"] ?: 0.0
                }
                return aggregate
            }
        }
    }

    companion object {
        const val MASSIF_PREFIX = "s"
        const val REGION_PREFIX = "r"
        const val GLOBAL_PREFIX = "g"
        private const val HISTORY_PREFIX = "h"
        private const val HIGH_RANK = 3
        private const val LOW_RANK = 1
        private const val CANYON_PRIOR_STRENGTH = 3.0
        private const val MASSIF_PRIOR_STRENGTH = 20.0
        private const val REGION_PRIOR_STRENGTH = 50.0
        private const val DELTA_MAX_DAYS = 300L
        private const val NEIGHBOR_PRIOR_STRENGTH = 3.0
        private const val NEIGHBOR_PRIOR_MEAN = 2.0
        private const val NEIGHBOR_CLOSE_KM = 10.0
        private const val RAIN_CLOSE_KM = 12.0
        private const val DEFAULT_SOIL_CAPACITY_MM = 250.0
        private val FALLBACK_BAND_OFFSETS_M = doubleArrayOf(0.0, 150.0, 300.0, 450.0, 600.0)
        private val BAND_KEYS = (1..5).map { "hydroBand${it}ElevationM" }
        private val RESERVOIR_NAMES = listOf("Q1", "Q3", "Q10", "Q30", "Q90")
        private val SEASON_WEIGHTS = listOf(-1 to 0.5, 0 to 1.0, 1 to 0.5)

        private fun smoothed(total: Double, weight: Double, prior: Double, strength: Double): Double =
            (total + strength * prior) / (weight + strength)

        private fun sumRange(values: DoubleArray, fromInclusive: Int, toExclusive: Int): Double {
            var sum = 0.0
            for (index in fromInclusive until toExclusive) sum += values[index]
            return sum
        }

        /** Same rule as hydro.fill_window: forward fill, then backward fill, else 10 degC. */
        internal fun fillTemperature(raw: Array<Double?>): DoubleArray {
            val filled = raw.copyOf()
            for (index in 1 until filled.size) {
                if (filled[index] == null) filled[index] = filled[index - 1]
            }
            for (index in filled.size - 2 downTo 0) {
                if (filled[index] == null) filled[index] = filled[index + 1]
            }
            return DoubleArray(filled.size) { filled[it] ?: 10.0 }
        }

        /**
         * Canyon history shipped in the runtime lookups (`h0`, `h1`, ... = epochDay * 10 + rank),
         * used only when the live debit list is unavailable.
         */
        internal fun decodeLookupHistory(values: Map<String, Double>): List<Pair<Long, Int>> = values.entries
            .filter { (key, _) -> key.startsWith(HISTORY_PREFIX) && key.drop(1).all(Char::isDigit) && key.length > 1 }
            .map { (_, encoded) ->
                val packed = round(encoded).toLong()
                (packed / 10) to (packed % 10).toInt()
            }

        private fun roundTo(value: Double, decimals: Int): Double {
            val factor = 10.0.pow(decimals)
            return round(value * factor) / factor
        }

        internal fun NiveauDebit.ordinalRank(): Int? = when (this) {
            NiveauDebit.SEC -> 0
            NiveauDebit.FILET -> 1
            NiveauDebit.CORRECT -> 2
            NiveauDebit.GROS -> 3
            NiveauDebit.TRES_GROS -> 4
            NiveauDebit.CRUE -> 5
            NiveauDebit.INCONNU -> null
        }
    }
}
