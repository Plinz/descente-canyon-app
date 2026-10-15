package fr.descentecanyon.app.domain.model

/** A canyon close to another one, to plan a weekend or find a plan B. */
data class NearbyCanyon(val summary: CanyonSummary, val distanceKm: Double)

/**
 * The [limit] canyons of [catalog] closest to [canyonId] within [maxDistanceKm], nearest first.
 * Empty when [canyonId] has no position in the catalog.
 */
fun nearestCanyons(canyonId: Int, catalog: List<CanyonSearchItem>, maxDistanceKm: Double, limit: Int): List<NearbyCanyon> {
    val origin = catalog.firstOrNull { it.id == canyonId } ?: return emptyList()
    val latitude = origin.representativeLat ?: return emptyList()
    val longitude = origin.representativeLng ?: return emptyList()
    return catalog.asSequence()
        .filter { it.id != canyonId }
        .mapNotNull { item ->
            val itemLatitude = item.representativeLat ?: return@mapNotNull null
            val itemLongitude = item.representativeLng ?: return@mapNotNull null
            val distance = haversineKm(latitude, longitude, itemLatitude, itemLongitude)
            if (distance <= maxDistanceKm) NearbyCanyon(item.toSummary(), distance) else null
        }
        .sortedWith(compareBy<NearbyCanyon> { it.distanceKm }.thenBy { it.summary.nom })
        .take(limit)
        .toList()
}
