package fr.descentecanyon.app.domain.model

/** A canyon around a position, with how far its route is from it. */
data class CanyonAround(val summary: CanyonSummary, val distanceKm: Double)

/**
 * Distance from a position to a canyon: to its route (start parking, entry, exit, end parking, in
 * that order, as straight lines) and to each of its points. Null for a canyon without point.
 */
fun distanceToCanyonKm(latitude: Double, longitude: Double, points: List<GeoPoint>): Double? {
    if (points.isEmpty()) return null
    val endParking = points.firstOrNull { it.type == GeoPointType.PARKING_AVAL }
    // Without an upstream parking, the approach starts from the downstream one.
    val route = listOfNotNull(
        points.firstOrNull { it.type == GeoPointType.PARKING_AMONT } ?: endParking,
        points.firstOrNull { it.type == GeoPointType.ENTREE },
        points.firstOrNull { it.type == GeoPointType.SORTIE },
        endParking,
    )
    val toRoute = route.zipWithNext { start, end ->
        distanceToSegmentKm(latitude, longitude, start.latitude, start.longitude, end.latitude, end.longitude)
    }
    val toPoints = points.map { haversineKm(latitude, longitude, it.latitude, it.longitude) }
    return (toRoute + toPoints).min()
}
