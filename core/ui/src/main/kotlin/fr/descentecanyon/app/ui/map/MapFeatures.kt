@file:Suppress("DEPRECATION")

package fr.descentecanyon.app.ui.map

import com.google.gson.JsonObject
import fr.descentecanyon.app.domain.model.CanyonSummary
import kotlin.math.roundToInt
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val CLUSTER_TAP_FALLBACK_ZOOM_DELTA = 2.0

private const val CLUSTER_TAP_MIN_ZOOM_DELTA = 1.5

private const val DETAIL_POINT_ZOOM_THRESHOLD = 8.4

private const val DETAIL_POINT_VISIBLE_COUNT_THRESHOLD = 12

internal const val USER_LOCATION_FOCUS_ZOOM = 9.6

internal fun LatLngBounds.toSignatureHash(): Int {
    var result = 17
    result = 31 * result + latitudeNorth.coordinateHash()
    result = 31 * result + longitudeEast.coordinateHash()
    result = 31 * result + latitudeSouth.coordinateHash()
    result = 31 * result + longitudeWest.coordinateHash()
    return result
}

private fun LatLngBounds.contains(latitude: Double, longitude: Double): Boolean {
    val latMatches = latitude in latitudeSouth..latitudeNorth
    if (!latMatches) return false
    return if (longitudeWest <= longitudeEast) {
        longitude in longitudeWest..longitudeEast
    } else {
        longitude >= longitudeWest || longitude <= longitudeEast
    }
}

internal fun Double?.coordinateHash(): Int = this?.times(1_000_000)?.roundToInt() ?: 0

internal fun normalizeBearing(bearing: Double): Double = (bearing % 360.0 + 360.0) % 360.0

internal fun shouldShowDetailPoints(zoom: Double, visibleMarkerCount: Int): Boolean = zoom >= DETAIL_POINT_ZOOM_THRESHOLD ||
    visibleMarkerCount in 1..DETAIL_POINT_VISIBLE_COUNT_THRESHOLD

internal fun clusterTapTargetZoom(currentZoom: Double, expansionZoom: Double?): Double =
    expansionZoom?.coerceAtLeast(currentZoom + CLUSTER_TAP_MIN_ZOOM_DELTA)
        ?: (currentZoom + CLUSTER_TAP_FALLBACK_ZOOM_DELTA)

internal fun buildCanyonFeatureCollection(markers: List<CanyonSummary>): FeatureCollection {
    val features = markers.mapNotNull { canyon ->
        val latitude = canyon.latitude ?: return@mapNotNull null
        val longitude = canyon.longitude ?: return@mapNotNull null
        Feature.fromGeometry(
            Point.fromLngLat(longitude, latitude),
            JsonObject().apply {
                addProperty(PROPERTY_CANYON_ID, canyon.id)
                addProperty(PROPERTY_CANYON_NAME, canyon.nom)
                addProperty(PROPERTY_INTEREST_COLOR, colorIntToMapColor(interestMarkerColor(canyon)))
                addProperty(PROPERTY_RENDER_PRIORITY, interestMarkerRenderPriority(canyon))
            },
        )
    }
    return FeatureCollection.fromFeatures(features)
}

internal fun filterMarkersInViewport(markers: List<CanyonSummary>, map: MapLibreMap): List<CanyonSummary> {
    val bounds = visibleBounds(map) ?: return markers
    return markers.filter { canyon ->
        val latitude = canyon.latitude ?: return@filter false
        val longitude = canyon.longitude ?: return@filter false
        bounds.contains(latitude, longitude)
    }
}

internal fun visibleBounds(map: MapLibreMap): LatLngBounds? = runCatching {
    map.projection.visibleRegion.latLngBounds
}.getOrNull()

internal fun buildUserFeatureCollection(latitude: Double?, longitude: Double?): FeatureCollection {
    if (latitude == null || longitude == null) return EMPTY_FEATURE_COLLECTION
    return FeatureCollection.fromFeature(
        Feature.fromGeometry(Point.fromLngLat(longitude, latitude)),
    )
}

internal fun toFeatureGeoJson(geometryJson: String): String = "{\"type\":\"Feature\",\"properties\":{},\"geometry\":$geometryJson}"
