package fr.descentecanyon.app.domain.model

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** Sunrise and sunset of a day; null when the sun does not rise or set (polar day or night). */
data class SunTimes(val sunrise: LocalTime?, val sunset: LocalTime?)

/**
 * Sunrise and sunset computed on the device (algorithm of the Almanac for Computers, about one
 * minute of error at canyon latitudes), so the daylight left is known without network.
 */
object SunCalculator {
    private const val OFFICIAL_ZENITH = 90.833

    fun sunTimes(date: LocalDate, latitude: Double, longitude: Double, zoneId: ZoneId): SunTimes = SunTimes(
        sunrise = event(date, latitude, longitude, zoneId, rising = true),
        sunset = event(date, latitude, longitude, zoneId, rising = false),
    )

    private fun event(date: LocalDate, latitude: Double, longitude: Double, zoneId: ZoneId, rising: Boolean): LocalTime? {
        val dayOfYear = date.dayOfYear
        val longitudeHour = longitude / 15.0
        val approximateTime = dayOfYear + ((if (rising) 6.0 else 18.0) - longitudeHour) / 24.0
        val meanAnomaly = 0.9856 * approximateTime - 3.289
        val trueLongitude = normalizeDegrees(
            meanAnomaly + 1.916 * sinDeg(meanAnomaly) + 0.020 * sinDeg(2 * meanAnomaly) + 282.634,
        )
        var rightAscension = normalizeDegrees(Math.toDegrees(atan(0.91764 * tanDeg(trueLongitude))))
        rightAscension += floor(trueLongitude / 90.0) * 90.0 - floor(rightAscension / 90.0) * 90.0
        rightAscension /= 15.0
        val sinDeclination = 0.39782 * sinDeg(trueLongitude)
        val cosDeclination = cos(asin(sinDeclination))
        val cosLocalHourAngle = (cosDeg(OFFICIAL_ZENITH) - sinDeclination * sinDeg(latitude)) / (cosDeclination * cosDeg(latitude))
        if (cosLocalHourAngle > 1.0 || cosLocalHourAngle < -1.0) return null
        val hourAngle = Math.toDegrees(acos(cosLocalHourAngle)).let { if (rising) 360.0 - it else it } / 15.0
        val localMeanTime = hourAngle + rightAscension - 0.06571 * approximateTime - 6.622
        val utcHours = normalizeHours(localMeanTime - longitudeHour)
        val utcSeconds = (utcHours * 3600).toLong()
        return date.atStartOfDay(ZoneOffset.UTC)
            .plusSeconds(utcSeconds)
            .withZoneSameInstant(zoneId)
            .toLocalTime()
            .withNano(0)
    }

    private fun sinDeg(degrees: Double) = sin(Math.toRadians(degrees))

    private fun cosDeg(degrees: Double) = cos(Math.toRadians(degrees))

    private fun tanDeg(degrees: Double) = tan(Math.toRadians(degrees))

    private fun normalizeDegrees(value: Double) = ((value % 360.0) + 360.0) % 360.0

    private fun normalizeHours(value: Double) = ((value % 24.0) + 24.0) % 24.0
}
