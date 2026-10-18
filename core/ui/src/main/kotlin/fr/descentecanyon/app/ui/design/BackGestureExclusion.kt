package fr.descentecanyon.app.ui.design

import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.Modifier

/**
 * Keeps the system back gesture away from a button near a side of the screen. Within about 30 dp
 * of each side, a tap that drifts by a millimetre is taken for the start of a back swipe: the
 * button shows its press but never gets the click. Android grants at most 200 dp of exclusion per
 * side, counted from the bottom, so only small buttons get it: past the limit, the top bar would
 * lose its own.
 */
fun Modifier.excludeFromBackGesture(): Modifier = systemGestureExclusion()
