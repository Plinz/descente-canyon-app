package fr.descentecanyon.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.Hint
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.test.TestTags
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Where the element of a hint is (in the root of the screen) and what the hint says. */
data class HintAnchor(val bounds: Rect, val title: String, val text: String)

/** The elements of the hints on screen, placed by [DcHint], drawn over by [HintOverlay]. */
class HintOverlayState {
    private val anchors = mutableStateMapOf<Hint, HintAnchor>()

    fun place(hint: Hint, anchor: HintAnchor) {
        if (anchors[hint] != anchor) anchors[hint] = anchor
    }

    fun remove(hint: Hint) {
        anchors.remove(hint)
    }

    operator fun get(hint: Hint): HintAnchor? = anchors[hint]
}

val LocalHintOverlay = staticCompositionLocalOf<HintOverlayState?> { null }

/**
 * The hint of [host], over the whole screen: the rest dimmed, its element lit and ringed, and a card
 * that says what it does, below the element or above it when there is no room. Tapping the element
 * uses it (and closes the hint), tapping elsewhere, "Compris" or Back closes it.
 */
@Composable
fun HintOverlay(state: HintOverlayState, host: OnboardingHost, modifier: Modifier = Modifier) {
    val current = host.currentHint
    // Once the screen has settled: a hint over a screen still sliding in is missed.
    var shown by remember { mutableStateOf<Hint?>(null) }
    LaunchedEffect(current) {
        shown = null
        if (current != null) {
            delay(HINT_DELAY_MS)
            shown = current
        }
    }
    val hint = shown ?: return
    val anchor = state[hint] ?: return
    val dismiss = { host.dismissHint(hint) }
    BackHandler(onBack = dismiss)

    val alpha = remember(hint) { Animatable(0f) }
    LaunchedEffect(hint) { alpha.animateTo(1f, tween(FADE_MS)) }
    val colors = LocalDcColors.current
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        // An element out of sight (scrolled away, under a bar) gets its hint once it is back in view.
        if (anchor.bounds.top < 0f || anchor.bounds.bottom > height || anchor.bounds.isEmpty) return@BoxWithConstraints
        val hole = with(density) { anchor.bounds.inflate(HOLE_MARGIN.toPx()) }
        val corner = with(density) { HOLE_CORNER.toPx() }
        val ring = with(density) { RING_WIDTH.toPx() }

        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(SCRIM)
            drawRoundRect(Color.Transparent, hole.topLeft, hole.size, CornerRadius(corner), blendMode = BlendMode.Clear)
            drawRoundRect(colors.primaryAction, hole.topLeft, hole.size, CornerRadius(corner), style = Stroke(width = ring))
        }
        // Taps around the lit element close the hint; the element itself is left free (no catcher over it).
        listOf(
            Rect(0f, 0f, width, hole.top),
            Rect(0f, hole.bottom, width, height),
            Rect(0f, hole.top, hole.left, hole.bottom),
            Rect(hole.right, hole.top, width, hole.bottom),
        ).filterNot { it.width <= 0f || it.height <= 0f }.forEach { area ->
            TapCatcher(area, onTap = dismiss)
        }
        HintCardLayout(hole = hole, maxWidth = width, maxHeight = height) {
            HintCard(hint = hint, title = anchor.title, text = anchor.text, onGotIt = dismiss)
        }
    }
}

@Composable
private fun TapCatcher(area: Rect, onTap: () -> Unit) {
    val density = LocalDensity.current
    with(density) {
        Box(
            modifier = Modifier
                .offset { IntOffset(area.left.roundToInt(), area.top.roundToInt()) }
                .size(area.width.toDp(), area.height.toDp())
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        )
    }
}

/** The card below the lit element, or above it when it would not fit, kept within the screen. */
@Composable
private fun HintCardLayout(hole: Rect, maxWidth: Float, maxHeight: Float, card: @Composable () -> Unit) {
    val density = LocalDensity.current
    Layout(content = card) { measurables, _ ->
        val margin = with(density) { SCREEN_MARGIN.roundToPx() }
        val gap = with(density) { CARD_GAP.roundToPx() }
        val cardWidth = minOf(maxWidth.roundToInt() - 2 * margin, with(density) { CARD_MAX_WIDTH.roundToPx() })
        val placeable = measurables.single().measure(Constraints(minWidth = cardWidth, maxWidth = cardWidth))
        layout(maxWidth.roundToInt(), maxHeight.roundToInt()) {
            val below = hole.bottom.roundToInt() + gap
            val above = hole.top.roundToInt() - gap - placeable.height
            val y = when {
                below + placeable.height <= maxHeight.roundToInt() - margin -> below
                above >= margin -> above
                else -> (maxHeight.roundToInt() - placeable.height) / 2
            }
            val x = (hole.center.x.roundToInt() - placeable.width / 2)
                .coerceIn(margin, (maxWidth.roundToInt() - margin - placeable.width).coerceAtLeast(margin))
            placeable.place(x, y)
        }
    }
}

@Composable
private fun HintCard(hint: Hint, title: String, text: String, onGotIt: () -> Unit) {
    val colors = LocalDcColors.current
    Surface(
        modifier = Modifier.testTag(TestTags.hint(hint.name)).semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceBase,
        contentColor = colors.textPrimary,
        border = BorderStroke(2.dp, colors.primaryAction),
        shadowElevation = 12.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Lightbulb, contentDescription = null, tint = colors.primaryAction, modifier = Modifier.size(22.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.primaryAction)
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
                Button(
                    onClick = onGotIt,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primaryAction, contentColor = colors.primaryActionContent),
                ) { Text(stringResource(R.string.hint_got_it)) }
            }
        }
    }
}

/** Dark enough over the light and the dark theme for the lit element and the card to stand out. */
private val SCRIM = Color.Black.copy(alpha = 0.7f)
private val HOLE_MARGIN = 6.dp
private val HOLE_CORNER = 18.dp
private val RING_WIDTH = 3.dp
private val SCREEN_MARGIN = 16.dp
private val CARD_GAP = 12.dp
private val CARD_MAX_WIDTH = 380.dp
private const val HINT_DELAY_MS = 700L
private const val FADE_MS = 220
