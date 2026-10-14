package fr.descentecanyon.app.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class DcWindowWidthSizeClass { Compact, Medium, Expanded }

@Composable
fun rememberDcWindowWidthSizeClass(): DcWindowWidthSizeClass {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp < 600 -> DcWindowWidthSizeClass.Compact
        widthDp < 840 -> DcWindowWidthSizeClass.Medium
        else -> DcWindowWidthSizeClass.Expanded
    }
}

@Composable
fun rememberDcContentWidth(maxWidth: Dp = 900.dp): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    return minOf(screenWidth, maxWidth)
}

@Composable
fun rememberDcScreenHorizontalPadding(): Dp = when (rememberDcWindowWidthSizeClass()) {
    DcWindowWidthSizeClass.Compact -> 16.dp
    DcWindowWidthSizeClass.Medium, DcWindowWidthSizeClass.Expanded -> 24.dp
}
