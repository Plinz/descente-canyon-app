package fr.descentecanyon.app.data.repository

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Conceptual hydrological state model, port of `scripts/debit_model/hydro.py`.
 *
 * Snow on 5 elevation bands (lapse rate, rain/snow partition, degree-day melt), GR4J production
 * store with Oudin PET, then a bank of linear reservoirs (1, 3, 10, 30, 90 days). Constants, update
 * order and initial state must stay identical to the Python reference: golden vectors exported by
 * `export_golden_vectors.py` are checked by `DebitFeatureBuilderGoldenTest`.
 */
object DebitHydroModel {

    const val WINDOW_DAYS = 400
    const val MIN_VALID_DAYS = 380
    const val BAND_COUNT = 5
    private const val LAPSE_RATE_C_PER_M = -0.0065
    private const val PRECIP_GRADIENT_PER_M = 0.0004
    private const val PRECIP_FACTOR_MIN = 0.5
    private const val PRECIP_FACTOR_MAX = 2.0
    private const val SNOW_ALL_BELOW_C = -1.0
    private const val RAIN_ALL_ABOVE_C = 2.0
    private const val DEGREE_DAY_FACTOR_MM = 3.5
    private const val SWE_CAP_MM = 2000.0
    private const val SNOW_COVER_SWE_MM = 5.0
    private const val INITIAL_SOIL_FILL = 0.5
    val RESERVOIR_DAYS = doubleArrayOf(1.0, 3.0, 10.0, 30.0, 90.0)

    class Result(
        /** Reservoir outflows after day J-1 (state when the canyon is descended on day J). */
        val morningQ: DoubleArray,
        /** Reservoir outflows after day J. */
        val eveningQ: DoubleArray,
        val morningSwe: Double,
        val morningSnowCover: Double,
        val morningSoilFill: Double,
        val liquidInput: DoubleArray,
        val melt: DoubleArray,
        val swe: DoubleArray,
        /** Morning reservoir state at [captureIndex], or null. */
        val capturedQ: DoubleArray?,
        val capturedSwe: Double?,
    )

    /**
     * @param precipitation daily precipitation (mm), target day last, already filled.
     * @param temperature daily mean temperature at [referenceElevationM] (degC), already filled.
     * @param dayOfYear day of year (1..366) of each window day.
     * @param captureIndex window index whose morning state is captured, or -1.
     */
    fun simulate(
        precipitation: DoubleArray,
        temperature: DoubleArray,
        dayOfYear: IntArray,
        latitudeDeg: Double,
        referenceElevationM: Double,
        bandElevationsM: DoubleArray,
        soilCapacityMm: Double,
        captureIndex: Int,
    ): Result {
        val days = precipitation.size
        val reservoirCount = RESERVOIR_DAYS.size
        val decay = DoubleArray(reservoirCount) { exp(-1.0 / RESERVOIR_DAYS[it]) }
        val temperatureOffset = DoubleArray(BAND_COUNT) { LAPSE_RATE_C_PER_M * (bandElevationsM[it] - referenceElevationM) }
        val precipFactor = DoubleArray(BAND_COUNT) {
            (1.0 + PRECIP_GRADIENT_PER_M * (bandElevationsM[it] - referenceElevationM)).coerceIn(PRECIP_FACTOR_MIN, PRECIP_FACTOR_MAX)
        }
        val x1 = soilCapacityMm
        val swe = DoubleArray(BAND_COUNT)
        var soil = INITIAL_SOIL_FILL * x1
        val q = DoubleArray(reservoirCount)

        val liquidHistory = DoubleArray(days)
        val meltHistory = DoubleArray(days)
        val sweHistory = DoubleArray(days)
        var capturedQ: DoubleArray? = null
        var capturedSwe: Double? = null
        var morningQ = DoubleArray(reservoirCount)
        var morningSwe = 0.0
        var morningSnowCover = 0.0
        var morningSoilFill = 0.0

        for (index in 0 until days) {
            if (index == captureIndex) {
                capturedQ = q.copyOf()
                capturedSwe = swe.average()
            }
            if (index == days - 1) {
                morningQ = q.copyOf()
                morningSwe = swe.average()
                morningSnowCover = swe.count { it > SNOW_COVER_SWE_MM }.toDouble() / BAND_COUNT
                morningSoilFill = soil / x1
            }
            var liquidSum = 0.0
            var meltSum = 0.0
            var temperatureSum = 0.0
            for (band in 0 until BAND_COUNT) {
                val bandTemperature = temperature[index] + temperatureOffset[band]
                val bandPrecipitation = precipitation[index] * precipFactor[band]
                val snowFraction = ((RAIN_ALL_ABOVE_C - bandTemperature) / (RAIN_ALL_ABOVE_C - SNOW_ALL_BELOW_C)).coerceIn(0.0, 1.0)
                val snow = bandPrecipitation * snowFraction
                val rain = bandPrecipitation - snow
                val accumulated = swe[band] + snow
                val melt = min(accumulated, max(0.0, DEGREE_DAY_FACTOR_MM * bandTemperature))
                swe[band] = min(accumulated - melt, SWE_CAP_MM)
                liquidSum += rain + melt
                meltSum += melt
                temperatureSum += bandTemperature
            }
            val liquid = liquidSum / BAND_COUNT
            val basinTemperature = temperatureSum / BAND_COUNT
            val pet = oudinPet(extraterrestrialRadiation(latitudeDeg, dayOfYear[index]), basinTemperature)

            val netRain = max(liquid - pet, 0.0)
            val netEvap = max(pet - liquid, 0.0)
            val fill = soil / x1
            val tp = tanh(netRain / x1)
            val storageGain = x1 * (1.0 - fill * fill) * tp / (1.0 + fill * tp)
            val te = tanh(netEvap / x1)
            val storageLoss = soil * (2.0 - fill) * te / (1.0 + (1.0 - fill) * te)
            soil = soil - storageLoss + storageGain
            val percolation = soil * (1.0 - (1.0 + (4.0 / 9.0 * soil / x1).pow(4)).pow(-0.25))
            soil -= percolation
            val runoff = percolation + (netRain - storageGain)
            for (reservoir in 0 until reservoirCount) {
                q[reservoir] = decay[reservoir] * q[reservoir] + (1.0 - decay[reservoir]) * runoff
            }

            liquidHistory[index] = liquid
            meltHistory[index] = meltSum / BAND_COUNT
            sweHistory[index] = swe.average()
        }

        return Result(
            morningQ = morningQ,
            eveningQ = q.copyOf(),
            morningSwe = morningSwe,
            morningSnowCover = morningSnowCover,
            morningSoilFill = morningSoilFill,
            liquidInput = liquidHistory,
            melt = meltHistory,
            swe = sweHistory,
            capturedQ = capturedQ,
            capturedSwe = capturedSwe,
        )
    }

    /** FAO-56 extraterrestrial radiation (MJ m-2 day-1). */
    fun extraterrestrialRadiation(latitudeDeg: Double, dayOfYear: Int): Double {
        val phi = Math.toRadians(latitudeDeg)
        val angle = 2.0 * PI * dayOfYear / 365.0
        val declination = 0.409 * sin(angle - 1.39)
        val inverseDistance = 1.0 + 0.033 * cos(angle)
        val sunset = acos((-tan(phi) * tan(declination)).coerceIn(-1.0, 1.0))
        return (24.0 * 60.0 / PI) * 0.0820 * inverseDistance *
            (sunset * sin(phi) * sin(declination) + cos(phi) * cos(declination) * sin(sunset))
    }

    private fun oudinPet(radiation: Double, temperatureC: Double): Double =
        max(0.0, radiation / 2.45 * (temperatureC + 5.0) / 100.0)
}
