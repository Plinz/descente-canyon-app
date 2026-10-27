package fr.descentecanyon.app.domain.model

import java.time.LocalDate

/** A place outings start from: home, a campsite, a gîte. A [Stay] gives the days spent at one. */
data class Place(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    /** When an outing last started from it (epoch ms), 0 if never. */
    val lastUsedAt: Long = 0,
    val id: Long = 0,
)

/**
 * The place an outing of [date] starts from by default: the place of the stay of that day, else the
 * place used last among those of no stay (home); a campsite of another week is not a default.
 */
fun List<Place>.defaultFor(date: LocalDate, stays: List<Stay>): Place? {
    stays.firstOrNull { it.covers(date) }?.let { stay -> firstOrNull { it.id == stay.placeId }?.let { return it } }
    val stayPlaces = stays.mapTo(mutableSetOf()) { it.placeId }
    return filter { it.lastUsedAt > 0 && it.id !in stayPlaces }.maxByOrNull { it.lastUsedAt }
}

/**
 * The starting place once the outing moves from [from] to [to]: the place by default follows the
 * day (the campsite of the stay, home after it); a place the user picked stays.
 */
fun List<Place>.placeOnNewDate(placeId: Long?, from: LocalDate, to: LocalDate, stays: List<Stay>): Long? {
    if (placeId != defaultFor(from, stays)?.id) return placeId
    return defaultFor(to, stays)?.id
}

enum class DriveTimeSource {
    /** Computed by a routing service on the road network. */
    ROUTE,

    /** Typed by the user, who knows the road. */
    MANUAL,

    /** Offline guess from the straight line. */
    ESTIMATE,
}

/** Driving time from a starting place to a canyon parking. */
data class DriveTime(val minutes: Int, val source: DriveTimeSource, val distanceKm: Double? = null) {
    companion object {
        /** Mountain roads wind: the road is about this much longer than the straight line. */
        const val DETOUR_FACTOR = 1.5
        const val ESTIMATE_SPEED_KMH = 45.0
        val MANUAL_RANGE = 0..600

        /** Without a route: the straight line stretched by [DETOUR_FACTOR], driven at [ESTIMATE_SPEED_KMH], rounded up to five minutes. */
        fun estimate(fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double): DriveTime {
            val km = haversineKm(fromLatitude, fromLongitude, toLatitude, toLongitude) * DETOUR_FACTOR
            return DriveTime(minutes = roundUpToFive(km / ESTIMATE_SPEED_KMH * 60), source = DriveTimeSource.ESTIMATE, distanceKm = km)
        }
    }
}

/**
 * Where the cars go: the end parking with a shuttle (a car is left there first), else the start
 * parking; the entry or any point of the canyon when the sheet has no parking.
 */
fun CanyonDetail.meetingParking(withShuttle: Boolean): GeoPoint? {
    val downstream = geoPoints.firstOrNull { it.type == GeoPointType.PARKING_AVAL }
    val upstream = geoPoints.firstOrNull { it.type == GeoPointType.PARKING_AMONT }
    val parking = if (withShuttle) downstream ?: upstream else upstream ?: downstream
    return parking ?: geoPoints.firstOrNull { it.type == GeoPointType.ENTREE } ?: geoPoints.firstOrNull()
}
