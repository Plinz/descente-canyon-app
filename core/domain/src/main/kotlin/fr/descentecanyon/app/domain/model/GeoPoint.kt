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
)

enum class GeoPointType {
    PARKING_AVAL,
    PARKING_AMONT,
    ENTREE,
    SORTIE,
    POINT_REMARQUABLE,
    ECHAPPATOIRE,
    UNKNOWN,
}
