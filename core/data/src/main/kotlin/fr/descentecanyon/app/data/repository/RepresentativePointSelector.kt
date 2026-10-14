package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.catalog.entity.GeoPointEntity
import fr.descentecanyon.app.domain.model.GeoPointType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Singleton
class RepresentativePointSelector @Inject constructor() {

    fun bestMarkerPoint(points: List<GeoPointEntity>): GeoPointEntity = bestMarkerPointOrNull(points) ?: points.first()

    fun bestMarkerPointOrNull(points: List<GeoPointEntity>): GeoPointEntity? = points.minByOrNull { point ->
        when (runCatching { GeoPointType.valueOf(point.type) }.getOrDefault(GeoPointType.UNKNOWN)) {
            GeoPointType.PARKING_AMONT -> 0
            GeoPointType.PARKING_AVAL -> 1
            GeoPointType.ENTREE -> 2
            GeoPointType.SORTIE -> 3
            GeoPointType.POINT_REMARQUABLE -> 4
            GeoPointType.ECHAPPATOIRE -> 5
            GeoPointType.UNKNOWN -> 6
        }
    }

    fun haversineKm(latitude: Double, longitude: Double, targetLatitude: Double, targetLongitude: Double): Double {
        val earthRadiusKm = 6371.0
        val latDistance = Math.toRadians(targetLatitude - latitude)
        val lonDistance = Math.toRadians(targetLongitude - longitude)
        val a = sin(latDistance / 2).pow(2.0) +
            cos(Math.toRadians(latitude)) * cos(Math.toRadians(targetLatitude)) *
            sin(lonDistance / 2).pow(2.0)

        return 2 * earthRadiusKm * asin(sqrt(a))
    }
}
