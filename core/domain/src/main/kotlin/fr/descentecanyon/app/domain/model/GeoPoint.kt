package fr.descentecanyon.app.domain.model

/**
 * A geolocated point related to a canyon (parking, entry, exit, etc.).
 */
data class GeoPoint(
    val id: Long = 0,
    val canyonId: Int,
    val type: GeoPointType,
    val latitude: Double,
    val longitude: Double,
    val title: String? = null,
    val remark: String? = null,
    val source: GeoPointSource = GeoPointSource.SITE,
)

/**
 * Where a point comes from. The site gives the points of most canyons; a few canyons without any point
 * got theirs from elsewhere, less certain, and the app says so.
 */
enum class GeoPointSource {
    /** The canyon sheet of the site. */
    SITE,

    /** Coordinates written in the topo text of the site. */
    TOPO,

    /** OpenCanyon.org, not checked on the ground. */
    OPENCANYON,

    /** Placed from the stream (OpenStreetMap) and the terrain at the altitudes of the topo. */
    ESTIMATED,
}

enum class GeoPointType {
    PARKING_AVAL,
    PARKING_AMONT,
    ENTREE,
    SORTIE,
    POINT_REMARQUABLE,
    ECHAPPATOIRE,
    UNKNOWN,
}
