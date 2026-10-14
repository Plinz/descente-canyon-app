@file:Suppress("DEPRECATION")

package fr.descentecanyon.app.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.GeoPointType
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.textSize

internal sealed interface AnnotationMarker {
    val latitude: Double
    val longitude: Double

    data class Canyon(val canyon: CanyonSummary, override val latitude: Double, override val longitude: Double) : AnnotationMarker

    data class User(override val latitude: Double, override val longitude: Double, val bearingDegrees: Double?) : AnnotationMarker
}

@ColorInt
internal fun interestMarkerColor(canyon: CanyonSummary): Int {
    if (canyon.isForbidden) return FORBIDDEN_CANYON_COLOR
    val interest = canyon.interet
    return when {
        interest == null || interest <= 0f -> INTEREST_UNKNOWN_COLOR
        interest <= 1f -> INTEREST_0_TO_1_COLOR
        interest <= 2f -> INTEREST_1_TO_2_COLOR
        interest <= 3f -> INTEREST_2_TO_3_COLOR
        else -> INTEREST_3_TO_4_COLOR
    }
}

internal fun interestMarkerRenderPriority(canyon: CanyonSummary): Int {
    val interest = canyon.interet
    return when {
        canyon.isForbidden -> 0
        interest == null || interest <= 0f -> 1
        interest <= 1f -> 2
        interest <= 2f -> 3
        interest <= 3f -> 4
        else -> 5
    }
}

internal fun colorIntToMapColor(@ColorInt color: Int): String = "#%06X".format(color and 0x00FFFFFF)

private fun CanyonSummary.markerIconRes(): Int = when (markerType) {
    GeoPointType.PARKING_AMONT -> R.drawable.map_marker_parking_amont
    GeoPointType.PARKING_AVAL -> R.drawable.map_marker_parking_aval
    GeoPointType.ENTREE -> R.drawable.map_marker_entry
    GeoPointType.SORTIE -> R.drawable.map_marker_exit
    GeoPointType.POINT_REMARQUABLE -> R.drawable.map_marker_remarkable
    GeoPointType.ECHAPPATOIRE -> R.drawable.map_marker_escape
    GeoPointType.UNKNOWN, null -> R.drawable.map_marker_point
}

private fun drawableToBitmap(context: android.content.Context, resId: Int): Bitmap {
    val drawable = ContextCompat.getDrawable(context, resId)
        ?: return createMarkerBitmap("?", 0xFF1A6B8A.toInt())
    val width = drawable.intrinsicWidth.coerceAtLeast(88)
    val height = drawable.intrinsicHeight.coerceAtLeast(88)
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, width, height)
    drawable.draw(canvas)
    return bitmap
}

private fun AnnotationMarker.toMarkerBitmap(context: android.content.Context): Bitmap = when (this) {
    is AnnotationMarker.Canyon -> drawableToBitmap(context, canyon.markerIconRes())
    is AnnotationMarker.User -> createUserMarkerBitmap(bearingDegrees)
}

private fun MarkerOptions.applyMarkerBitmap(
    iconFactory: IconFactory,
    marker: AnnotationMarker,
    context: android.content.Context,
): MarkerOptions = runCatching {
    icon(iconFactory.fromBitmap(marker.toMarkerBitmap(context)))
}.onFailure { throwable ->
    Log.e(TAG, "Unable to load marker icon bitmap", throwable)
}.getOrDefault(this)

internal fun AnnotationMarker.toMarkerOptions(iconFactory: IconFactory, context: android.content.Context): MarkerOptions = when (this) {
    is AnnotationMarker.Canyon -> MarkerOptions()
        .position(LatLng(latitude, longitude))
        .title(canyon.nom)
        .snippet("canyon:${canyon.id}")
        .applyMarkerBitmap(iconFactory, this, context)

    is AnnotationMarker.User -> MarkerOptions()
        .position(LatLng(latitude, longitude))
        .title(context.getString(R.string.map_user_position_title))
        .snippet("user")
        .applyMarkerBitmap(iconFactory, this, context)
}

private fun createMarkerBitmap(label: String, colorInt: Int): Bitmap {
    val width = 132
    val height = 64
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorInt }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 28f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    val rect = Rect(8, 8, width - 8, height - 8)
    canvas.drawRoundRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), 22f, 22f, fillPaint)
    canvas.drawRoundRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), 22f, 22f, strokePaint)
    val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(label.take(4), width / 2f, y, textPaint)
    return bitmap
}

private fun createUserMarkerBitmap(bearingDegrees: Double?): Bitmap {
    val width = 120
    val height = 144
    val centerX = width / 2f
    val anchorY = 117f
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = USER_HALO_COLOR }
    val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = USER_STROKE_COLOR }
    val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = USER_POINT_COLOR }

    canvas.drawCircle(centerX, anchorY - 18f, 42f, haloPaint)
    if (bearingDegrees == null) {
        canvas.drawCircle(centerX, anchorY - 18f, 22f, whitePaint)
        canvas.drawCircle(centerX, anchorY - 18f, 16f, bluePaint)
    } else {
        canvas.withRotation(bearingDegrees.toFloat(), centerX, anchorY - 18f) {
            val arrow = Path().apply {
                moveTo(centerX, anchorY - 57f)
                lineTo(centerX - 22f, anchorY + 12f)
                lineTo(centerX, anchorY + 2f)
                lineTo(centerX + 22f, anchorY + 12f)
                close()
            }
            drawPath(arrow, whitePaint)
            scale(0.72f, 0.72f, centerX, anchorY - 18f)
            drawPath(arrow, bluePaint)
        }
    }

    return bitmap
}
