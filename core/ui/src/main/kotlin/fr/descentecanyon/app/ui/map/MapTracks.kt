package fr.descentecanyon.app.ui.map

import androidx.annotation.ColorInt
import fr.descentecanyon.app.domain.model.CommunityTrack
import fr.descentecanyon.app.domain.model.TrackPoint
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

/**
 * Colour of the track at [index] in the canyon's list (reference tracks first): every track has its
 * own colour, the same on the canyon page and on the map, so the list is the map's legend.
 */
@ColorInt
fun trackColor(index: Int): Int = TRACK_PALETTE[index.mod(TRACK_PALETTE.size)]

private val TRACK_PALETTE = intArrayOf(
    0xFF1E6FD9.toInt(), // blue
    0xFFE8590C.toInt(), // orange
    0xFF8E44AD.toInt(), // purple
    0xFF2E9D4B.toInt(), // green
    0xFFC2185B.toInt(), // magenta
    0xFF00838F.toInt(), // teal
    0xFF6D4C41.toInt(), // brown
    0xFF455A64.toInt(), // slate
)

fun CommunityTrack.toMapTrackLine(index: Int): MapTrackLine =
    MapTrackLine(id = id, segments = segments, color = trackColor(index), emphasized = isReference)

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
