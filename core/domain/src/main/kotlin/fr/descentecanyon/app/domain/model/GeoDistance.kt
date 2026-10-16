package fr.descentecanyon.app.domain.model

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_KM = 6371.0
private const val KM_PER_DEGREE = EARTH_RADIUS_KM * PI / 180.0

/** Great-circle distance between two WGS84 points, in kilometres. */
fun haversineKm(latitude: Double, longitude: Double, targetLatitude: Double, targetLongitude: Double): Double {
    val latDistance = Math.toRadians(targetLatitude - latitude)
    val lonDistance = Math.toRadians(targetLongitude - longitude)
    val a = sin(latDistance / 2).pow(2.0) +
        cos(Math.toRadians(latitude)) * cos(Math.toRadians(targetLatitude)) * sin(lonDistance / 2).pow(2.0)
    return 2 * EARTH_RADIUS_KM * asin(sqrt(a))
}

/** Direction to follow from the first point to reach the second, in degrees clockwise from north. */
fun bearingDegrees(latitude: Double, longitude: Double, targetLatitude: Double, targetLongitude: Double): Double {
    val startLatitude = Math.toRadians(latitude)
    val endLatitude = Math.toRadians(targetLatitude)
    val lonDistance = Math.toRadians(targetLongitude - longitude)
    val y = sin(lonDistance) * cos(endLatitude)
    val x = cos(startLatitude) * sin(endLatitude) - sin(startLatitude) * cos(endLatitude) * cos(lonDistance)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/**
 * Distance from a position to the segment between two points, in kilometres. Computed on a flat
 * projection around the position, precise enough over the few kilometres of a canyon.
 */
fun distanceToSegmentKm(
    latitude: Double,
    longitude: Double,
    startLatitude: Double,
    startLongitude: Double,
    endLatitude: Double,
    endLongitude: Double,
): Double {
    val kmPerLongitudeDegree = KM_PER_DEGREE * cos(Math.toRadians(latitude))
    // The position is the origin of the projection.
    val ax = (startLongitude - longitude) * kmPerLongitudeDegree
    val ay = (startLatitude - latitude) * KM_PER_DEGREE
    val dx = (endLongitude - longitude) * kmPerLongitudeDegree - ax
    val dy = (endLatitude - latitude) * KM_PER_DEGREE - ay
    val lengthSquared = dx * dx + dy * dy
    val t = if (lengthSquared == 0.0) 0.0 else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
    val x = ax + t * dx
    val y = ay + t * dy
    return sqrt(x * x + y * y)
}

/** The eight points of the compass, to say which way a place is. */
enum class CompassDirection {
    N,
    NE,
    E,
    SE,
    S,
    SW,
    W,
    NW,
    ;

    companion object {
        fun of(bearingDegrees: Double): CompassDirection {
            val normalized = (bearingDegrees % 360.0 + 360.0) % 360.0
            return entries[((normalized + 22.5) / 45.0).toInt() % entries.size]
        }
    }
}
