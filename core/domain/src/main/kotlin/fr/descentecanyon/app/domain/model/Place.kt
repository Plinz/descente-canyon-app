package fr.descentecanyon.app.domain.model

import java.time.LocalDate

/**
 * A place outings start from: home, a campsite, a gîte. [stayStart]..[stayEnd] are the days of a
 * stay there, when the user prepares several outings from it.
 */
data class Place(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val stayStart: LocalDate? = null,
    val stayEnd: LocalDate? = null,
    /** When an outing last started from it (epoch ms), 0 if never. */
    val lastUsedAt: Long = 0,
    val id: Long = 0,
) {
    fun hostsStayOn(date: LocalDate): Boolean = stayStart != null && stayEnd != null && date in stayStart..stayEnd
}

/**
 * The place an outing of [date] starts from by default: the stay of that day, else the place used
 * last among those without stay dates (home); a campsite of another week is not a default.
 */
fun List<Place>.defaultFor(date: LocalDate): Place? =
    firstOrNull { it.hostsStayOn(date) } ?: filter { it.lastUsedAt > 0 && it.stayStart == null }.maxByOrNull { it.lastUsedAt }

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
