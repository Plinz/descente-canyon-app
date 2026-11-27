package fr.descentecanyon.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Width of the world at zoom 0 in the map's density-independent pixels, and its length at the equator. */
private const val WORLD_TILE_DP = 512.0
private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686
private const val SCALE_MAX_WIDTH_DP = 96.0

/** Metres covered by one dp of the map at [latitude] and [zoom]. */
fun metersPerDp(latitude: Double, zoom: Double): Double =
    EARTH_CIRCUMFERENCE_M * cos(latitude * PI / 180.0) / (WORLD_TILE_DP * 2.0.pow(zoom))

/** The longest round distance (1, 2 or 5 times a power of ten) that fits in [maxDp], with its width. */
fun scaleBarStep(metersPerDp: Double, maxDp: Double = SCALE_MAX_WIDTH_DP): Pair<Double, Double> {
    val maxMeters = metersPerDp * maxDp
    val power = 10.0.pow(floor(log10(maxMeters)))
    val meters = listOf(5.0, 2.0, 1.0).map { it * power }.first { it <= maxMeters }
    return meters to meters / metersPerDp
}

/** A scale bar for the camera at [latitude] and [zoom]: "500 m", "2 km" over a line of that length. */
@Composable
fun MapScaleBar(latitude: Double, zoom: Double, modifier: Modifier = Modifier) {
    val (meters, widthDp) = scaleBarStep(metersPerDp(latitude, zoom))
    val locale = LocalConfiguration.current.locales[0]
    val label = if (meters >= 1_000) {
        stringResource(R.string.track_length_km, String.format(locale, "%.0f", meters / 1_000))
    } else {
        stringResource(R.string.track_length_m, meters.toInt())
    }
    val ink = Color(0xFF1B2327)
    Surface(
        modifier = modifier.clearAndSetSemantics {},
        color = Color.White.copy(alpha = 0.82f),
        contentColor = ink,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Canvas(modifier = Modifier.width(widthDp.dp).height(6.dp)) {
                val stroke = 2.dp.toPx()
                val y = size.height - stroke / 2
                drawLine(ink, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Square)
                drawLine(ink, start = Offset(stroke / 2, 0f), end = Offset(stroke / 2, y), strokeWidth = stroke)
                drawLine(ink, start = Offset(size.width - stroke / 2, 0f), end = Offset(size.width - stroke / 2, y), strokeWidth = stroke)
            }
        }
    }
}
