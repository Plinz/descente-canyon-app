package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.GeoBounds
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.WeatherLocationSource
import fr.descentecanyon.app.domain.model.WeatherTarget

internal object WeatherTargetResolver {

    fun resolve(detail: CanyonDetail): WeatherTarget? {
        detail.watershed?.bounds?.let { bounds ->
            return WeatherTarget(
                latitude = bounds.centerLatitude(),
                longitude = bounds.centerLongitude(),
                source = WeatherLocationSource.WATERSHED_CENTER,
            )
        }

        return detail.geoPoints
            .minByOrNull(::weatherPriority)
            ?.toWeatherTarget()
    }

    private fun weatherPriority(point: GeoPoint): Int = when (point.type) {
        GeoPointType.ENTREE -> 0
        GeoPointType.PARKING_AMONT -> 1
        GeoPointType.SORTIE -> 2
        GeoPointType.PARKING_AVAL -> 3
        GeoPointType.POINT_REMARQUABLE -> 4
        GeoPointType.ECHAPPATOIRE -> 5
        GeoPointType.UNKNOWN -> 6
    }

    private fun GeoPoint.toWeatherTarget(): WeatherTarget = WeatherTarget(
        latitude = latitude,
        longitude = longitude,
        source = when (type) {
            GeoPointType.ENTREE -> WeatherLocationSource.ENTRY
            GeoPointType.PARKING_AMONT -> WeatherLocationSource.UPSTREAM_PARKING
            GeoPointType.SORTIE -> WeatherLocationSource.EXIT
            GeoPointType.PARKING_AVAL -> WeatherLocationSource.DOWNSTREAM_PARKING
            GeoPointType.POINT_REMARQUABLE -> WeatherLocationSource.REMARKABLE_POINT
            GeoPointType.ECHAPPATOIRE -> WeatherLocationSource.ESCAPE
            GeoPointType.UNKNOWN -> WeatherLocationSource.UNKNOWN
        },
    )

    private fun GeoBounds.centerLatitude(): Double = (minLatitude + maxLatitude) / 2.0

    private fun GeoBounds.centerLongitude(): Double = (minLongitude + maxLongitude) / 2.0
}
