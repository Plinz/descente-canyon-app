package fr.descentecanyon.app.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.annotation.ColorInt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.createBitmap
import com.google.gson.JsonObject
import fr.descentecanyon.app.domain.model.GeoPointType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOffset
import org.maplibre.android.style.layers.PropertyFactory.iconPadding
import org.maplibre.android.style.layers.PropertyFactory.symbolSortKey
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * A point of a canyon on its map: a disc in the colour of its kind with a pictogram (P, start,
 * exit...), and its name under it while there is room. [id] comes back through the point taps.
 */
data class MapPoint(val id: Int, val latitude: Double, val longitude: Double, val type: GeoPointType, val label: String)

/** Colour of a kind of point, on the map and in the lists that act as its legend; white pictograms read on all of them. */
@ColorInt
fun GeoPointType.pointColor(): Int = when (this) {
    GeoPointType.PARKING_AMONT -> 0xFF1A6B8A.toInt()
    GeoPointType.PARKING_AVAL -> 0xFF6D28D9.toInt()
    GeoPointType.ENTREE -> 0xFF2E7D32.toInt()
    GeoPointType.SORTIE -> 0xFFC62828.toInt()
    GeoPointType.POINT_REMARQUABLE -> 0xFF8A5A00.toInt()
    GeoPointType.ECHAPPATOIRE -> 0xFFC2410C.toInt()
    GeoPointType.UNKNOWN -> 0xFF546E7A.toInt()
}

/** The marker of a kind of point as the map draws it, for the lists and chips that name the points. */
@Composable
fun PointMarker(type: GeoPointType, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        drawIntoCanvas { canvas ->
            val radius = this.size.minDimension / 2f
            drawPointMarker(canvas.nativeCanvas, type, center.x, center.y, radius * 0.9f, density)
        }
    }
}

/** The colour of a kind of point for Compose texts and shapes. */
fun GeoPointType.pointComposeColor(): Color = Color(pointColor())

internal const val POINT_SOURCE_ID = "canyon-point-source"
internal const val POINT_MARKER_LAYER_ID = "canyon-point-marker-layer"
private const val POINT_LABEL_LAYER_ID = "canyon-point-label-layer"
private const val POINT_HALO_LAYER_ID = "canyon-point-halo-layer"
internal const val PROPERTY_POINT_ID = "pointId"
private const val PROPERTY_POINT_ICON = "icon"
private const val PROPERTY_POINT_LABEL = "label"
private const val PROPERTY_MARKER_SORT = "markerSort"
private const val PROPERTY_LABEL_SORT = "labelSort"
private const val PROPERTY_POINT_SELECTED = "selected"
private const val PROPERTY_POINT_COLOR = "color"

/** Radius of a marker disc, and of the selected one. */
private const val MARKER_RADIUS_DP = 13f
private const val SELECTED_MARKER_RADIUS_DP = 16f

/** A marker image is this much wider than its disc (its shadow), and its name starts this far below the image. */
private const val MARKER_MARGIN = 1.15f
private const val LABEL_GAP_DP = 2f

/** Labels give way to the markers and to each other; under this zoom the points are too close for them. */
private const val LABEL_MIN_ZOOM = 11.5f
private const val LABEL_MAX_WIDTH_DP = 150f

private fun markerImageId(type: GeoPointType, selected: Boolean) = "canyon-point-${type.name}" + if (selected) "-selected" else ""

private fun labelImageId(point: MapPoint) = "canyon-point-label-${point.id}"

/** Adds the source, the marker images and the two layers (labels under the markers) once per style. */
internal fun ensurePointStyle(style: Style, density: Float) {
    GeoPointType.entries.forEach { type ->
        listOf(false, true).forEach { selected ->
            val id = markerImageId(type, selected)
            if (style.getImage(id) == null) style.addImage(id, markerBitmap(type, selected, density))
        }
    }
    if (style.getSource(POINT_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(POINT_SOURCE_ID, EMPTY_GEOJSON))
    }
    if (style.getLayer(POINT_LABEL_LAYER_ID) == null) {
        style.addLayer(
            SymbolLayer(POINT_LABEL_LAYER_ID, POINT_SOURCE_ID).withProperties(
                iconImage(Expression.get(PROPERTY_POINT_LABEL)),
                iconAnchor(Property.ICON_ANCHOR_TOP),
                // Just under the marker, bigger when selected; the boxes of both stay apart, or the name would give way to its own marker.
                iconOffset(
                    Expression.switchCase(
                        Expression.get(PROPERTY_POINT_SELECTED),
                        Expression.literal(arrayOf(0f, SELECTED_MARKER_RADIUS_DP * MARKER_MARGIN + LABEL_GAP_DP)),
                        Expression.literal(arrayOf(0f, MARKER_RADIUS_DP * MARKER_MARGIN + LABEL_GAP_DP)),
                    ),
                ),
                iconPadding(0f),
                iconAllowOverlap(false),
                iconIgnorePlacement(false),
                symbolSortKey(Expression.get(PROPERTY_LABEL_SORT)),
            ).apply { minZoom = LABEL_MIN_ZOOM },
        )
    }
    if (style.getLayer(POINT_HALO_LAYER_ID) == null) {
        // The halo of the selected point is a circle: it takes no room from the names.
        style.addLayer(
            CircleLayer(POINT_HALO_LAYER_ID, POINT_SOURCE_ID)
                .withFilter(Expression.eq(Expression.get(PROPERTY_POINT_SELECTED), Expression.literal(true)))
                .withProperties(
                    circleColor(Expression.toColor(Expression.get(PROPERTY_POINT_COLOR))),
                    circleOpacity(0.32f),
                    circleRadius(SELECTED_MARKER_RADIUS_DP * 1.6f),
                ),
        )
    }
    if (style.getLayer(POINT_MARKER_LAYER_ID) == null) {
        style.addLayer(
            SymbolLayer(POINT_MARKER_LAYER_ID, POINT_SOURCE_ID).withProperties(
                iconImage(Expression.get(PROPERTY_POINT_ICON)),
                iconAnchor(Property.ICON_ANCHOR_CENTER),
                // Always drawn, and their room is kept free of names.
                iconAllowOverlap(true),
                iconIgnorePlacement(false),
                iconPadding(0f),
                symbolSortKey(Expression.get(PROPERTY_MARKER_SORT)),
            ),
        )
    }
}

/** Draws one label image per point (its name in a white pill), replaced when the names change. */
internal fun addPointLabelImages(style: Style, points: List<MapPoint>, density: Float) {
    points.filter {
        it.label.isNotBlank()
    }.forEach { point -> style.addImage(labelImageId(point), labelBitmap(point.label.trim(), density)) }
}

/** The points, the selected one drawn on top and its name placed first. */
internal fun buildPointGeoJson(points: List<MapPoint>, selectedId: Int?): String {
    if (points.isEmpty()) return EMPTY_GEOJSON
    val features = points.mapIndexed { index, point ->
        val selected = point.id == selectedId
        Feature.fromGeometry(
            Point.fromLngLat(point.longitude, point.latitude),
            JsonObject().apply {
                addProperty(PROPERTY_POINT_ID, point.id)
                addProperty(PROPERTY_POINT_ICON, markerImageId(point.type, selected))
                // A point without a name has no label image.
                if (point.label.isNotBlank()) addProperty(PROPERTY_POINT_LABEL, labelImageId(point))
                addProperty(PROPERTY_MARKER_SORT, if (selected) points.size + 1 else index)
                addProperty(PROPERTY_LABEL_SORT, if (selected) -1 else index)
                addProperty(PROPERTY_POINT_SELECTED, selected)
                addProperty(PROPERTY_POINT_COLOR, colorIntToMapColor(point.type.pointColor()))
            },
        )
    }
    return FeatureCollection.fromFeatures(features).toJson()
}

internal fun pointSignature(points: List<MapPoint>, selectedId: Int?): Int = points.fold(23 * 31 + (selectedId ?: -1)) { acc, point ->
    31 * (31 * (31 * acc + point.id) + point.latitude.coordinateHash() + point.longitude.coordinateHash()) +
        point.type.ordinal * 7 + point.label.hashCode()
}

private fun markerBitmap(type: GeoPointType, selected: Boolean, density: Float): Bitmap {
    val radius = (if (selected) SELECTED_MARKER_RADIUS_DP else MARKER_RADIUS_DP) * density
    // Room for the shadow under the disc.
    val size = (radius * MARKER_MARGIN * 2).toInt()
    val bitmap = createBitmap(size, size)
    drawPointMarker(Canvas(bitmap), type, size / 2f, size / 2f, radius, density)
    return bitmap
}

/** The marker of a point: a shadow, the disc in the colour of its kind ringed with white, and the white pictogram of the kind. */
internal fun drawPointMarker(canvas: Canvas, type: GeoPointType, cx: Float, cy: Float, radius: Float, density: Float) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = type.pointColor() }
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
    canvas.drawCircle(cx, cy + density, radius + density * 0.5f, shadow)
    canvas.drawCircle(cx, cy, radius, fill)
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = radius * 0.16f
    }
    canvas.drawCircle(cx, cy, radius - ring.strokeWidth / 2f, ring)
    drawPictogram(canvas, type, cx, cy, radius)
}

private fun drawPictogram(canvas: Canvas, type: GeoPointType, cx: Float, cy: Float, radius: Float) {
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    when (type) {
        GeoPointType.PARKING_AMONT, GeoPointType.PARKING_AVAL -> {
            val text = TextPaint(white).apply {
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textSize = radius * 1.25f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("P", cx, cy - (text.descent() + text.ascent()) / 2f, text)
        }

        // Start: a "play" triangle, its weight centred in the disc.
        GeoPointType.ENTREE -> canvas.drawPath(
            Path().apply {
                moveTo(cx - radius * 0.3f, cy - radius * 0.46f)
                lineTo(cx + radius * 0.5f, cy)
                lineTo(cx - radius * 0.3f, cy + radius * 0.46f)
                close()
            },
            white,
        )

        // Exit: a "stop" square.
        GeoPointType.SORTIE -> {
            val side = radius * 0.72f
            canvas.drawRoundRect(RectF(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2), radius * 0.08f, radius * 0.08f, white)
        }

        GeoPointType.POINT_REMARQUABLE -> canvas.drawPath(starPath(cx, cy + radius * 0.04f, radius * 0.6f, radius * 0.26f), white)

        // Escape route: an arrow leaving the canyon.
        GeoPointType.ECHAPPATOIRE -> {
            val stroke = Paint(white).apply {
                style = Paint.Style.STROKE
                strokeWidth = radius * 0.2f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val reach = radius * 0.38f
            canvas.drawLine(cx - reach, cy + reach, cx + reach, cy - reach, stroke)
            canvas.drawPath(
                Path().apply {
                    moveTo(cx - reach * 0.15f, cy - reach)
                    lineTo(cx + reach, cy - reach)
                    lineTo(cx + reach, cy + reach * 0.15f)
                },
                stroke,
            )
        }

        GeoPointType.UNKNOWN -> canvas.drawCircle(cx, cy, radius * 0.3f, white)
    }
}

private fun starPath(cx: Float, cy: Float, outer: Float, inner: Float): Path = Path().apply {
    for (i in 0 until 10) {
        val angle = -PI / 2 + i * PI / 5
        val r = if (i % 2 == 0) outer else inner
        val x = cx + (r * cos(angle)).toFloat()
        val y = cy + (r * sin(angle)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** The name of a point in a white pill, drawn with the system font so that it needs no download. */
private fun labelBitmap(label: String, density: Float): Bitmap {
    val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1B2327.toInt()
        textSize = 12f * density
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    val shown = TextUtils.ellipsize(label, text, LABEL_MAX_WIDTH_DP * density, TextUtils.TruncateAt.END).toString()
    val paddingX = 6f * density
    val paddingY = 3f * density
    val width = (text.measureText(shown) + paddingX * 2).toInt() + 2
    val height = (text.descent() - text.ascent() + paddingY * 2).toInt() + 2
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    val pill = RectF(1f, 1f, width - 1f, height - 1f)
    val corner = height / 2f
    canvas.drawRoundRect(pill, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() })
    canvas.drawRoundRect(
        pill,
        corner,
        corner,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33000000
            style = Paint.Style.STROKE
            strokeWidth = density * 0.75f
        },
    )
    canvas.drawText(shown, paddingX + 1f, 1f + paddingY - text.ascent(), text)
    return bitmap
}
