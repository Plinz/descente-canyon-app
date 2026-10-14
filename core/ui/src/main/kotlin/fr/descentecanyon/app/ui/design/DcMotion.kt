package fr.descentecanyon.app.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.staticCompositionLocalOf

data class DcMotion(
    val screenTransitionMillis: Int = 220,
    val accordionMillis: Int = 180,
    val overlayMillis: Int = 180,
    val easing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
)

val DcMotionDefault = DcMotion()
val LocalDcMotion = staticCompositionLocalOf { DcMotionDefault }
