package fr.descentecanyon.app.domain.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_KM = 6371.0

/** Great-circle distance between two WGS84 points, in kilometres. */
fun haversineKm(latitude: Double, longitude: Double, targetLatitude: Double, targetLongitude: Double): Double {
    val latDistance = Math.toRadians(targetLatitude - latitude)
    val lonDistance = Math.toRadians(targetLongitude - longitude)
    val a = sin(latDistance / 2).pow(2.0) +
        cos(Math.toRadians(latitude)) * cos(Math.toRadians(targetLatitude)) * sin(lonDistance / 2).pow(2.0)
    return 2 * EARTH_RADIUS_KM * asin(sqrt(a))
}
