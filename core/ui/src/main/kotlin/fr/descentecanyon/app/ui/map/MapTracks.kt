package fr.descentecanyon.app.ui.map

import androidx.annotation.ColorInt
import fr.descentecanyon.app.domain.model.CommunityTrack
import fr.descentecanyon.app.domain.model.TrackPoint
import fr.descentecanyon.app.domain.model.TrackRole
import java.util.Locale
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource

/** A line drawn on the map: a shared GPS track, in the colour of its role. */
data class MapTrackLine(val id: String, val segments: List<List<TrackPoint>>, @param:ColorInt val color: Int, val emphasized: Boolean)

/** Colour of a track role, the same in the track list and on the map (the list is the legend). */
@ColorInt
fun trackRoleColor(role: TrackRole): Int = when (role) {
    TrackRole.APPROACH -> 0xFF1E88E5.toInt()
    TrackRole.RETURN -> 0xFF2E9D4B.toInt()
    TrackRole.ESCAPE -> 0xFFF28C00.toInt()
    TrackRole.FULL -> 0xFF8E44AD.toInt()
}

fun CommunityTrack.toMapTrackLine(): MapTrackLine =
    MapTrackLine(id = id, segments = segments, color = trackRoleColor(role), emphasized = isReference)

internal const val TRACK_SOURCE_ID = "track-source"
private const val TRACK_CASING_LAYER_ID = "track-casing-layer"
private const val TRACK_LINE_LAYER_ID = "track-line-layer"
private const val PROPERTY_COLOR = "color"
private const val PROPERTY_WIDTH = "width"

internal fun ensureTrackStyle(style: Style) {
    if (style.getSource(TRACK_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(TRACK_SOURCE_ID, EMPTY_GEOJSON))
    }
    if (style.getLayer(TRACK_CASING_LAYER_ID) == null) {
        style.addLayer(
            LineLayer(TRACK_CASING_LAYER_ID, TRACK_SOURCE_ID).withProperties(
                lineColor("#FFFFFF"),
                lineOpacity(0.85f),
                lineWidth(Expression.sum(Expression.get(PROPERTY_WIDTH), Expression.literal(2.5f))),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
    }
    if (style.getLayer(TRACK_LINE_LAYER_ID) == null) {
        style.addLayer(
            LineLayer(TRACK_LINE_LAYER_ID, TRACK_SOURCE_ID).withProperties(
                lineColor(Expression.toColor(Expression.get(PROPERTY_COLOR))),
                lineWidth(Expression.get(PROPERTY_WIDTH)),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
    }
}

/** GeoJSON of the tracks; reference tracks are drawn wider and last (above the others). */
internal fun buildTrackGeoJson(tracks: List<MapTrackLine>): String {
    if (tracks.isEmpty()) return EMPTY_GEOJSON
    val features = tracks.sortedBy { it.emphasized }.joinToString(",") { track ->
        val lines = track.segments.filter { it.size >= 2 }.joinToString(",") { segment ->
            segment.joinToString(",", prefix = "[", postfix = "]") { point ->
                String.format(Locale.ROOT, "[%.6f,%.6f]", point.longitude, point.latitude)
            }
        }
        val color = String.format(Locale.ROOT, "#%06X", track.color and 0xFFFFFF)
        val width = if (track.emphasized) 5.0 else 3.5
        """{"type":"Feature","properties":{"$PROPERTY_COLOR":"$color","$PROPERTY_WIDTH":$width},"geometry":{"type":"MultiLineString","coordinates":[$lines]}}"""
    }
    return """{"type":"FeatureCollection","features":[$features]}"""
}

internal fun trackSignature(tracks: List<MapTrackLine>): Int = tracks.fold(19) { acc, track ->
    31 * (31 * (31 * acc + track.id.hashCode()) + track.color) + track.emphasized.hashCode() + track.segments.sumOf { it.size }
}
