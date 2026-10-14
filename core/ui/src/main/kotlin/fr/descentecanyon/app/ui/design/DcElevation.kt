package fr.descentecanyon.app.ui.design

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class DcElevation(val flat: Dp = 0.dp, val raised: Dp = 2.dp, val floating: Dp = 6.dp)

val DcElevationDefault = DcElevation()
val LocalDcElevation = staticCompositionLocalOf { DcElevationDefault }
