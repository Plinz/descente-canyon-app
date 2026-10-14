package fr.descentecanyon.app.ui.design

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class DcSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val section: Dp = 32.dp,
    val screenHorizontal: Dp = 16.dp,
)

val DcSpacingDefault = DcSpacing()
val LocalDcSpacing = staticCompositionLocalOf { DcSpacingDefault }
