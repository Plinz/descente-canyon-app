package fr.descentecanyon.app.ui.design

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

@Composable
fun DcTheme(darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dcColors = if (darkTheme) DcDarkColors else DcLightColors
    val materialColors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        dcColors.toMaterialColorScheme(darkTheme)
    }

    CompositionLocalProvider(
        LocalDcColors provides dcColors,
        LocalDcSpacing provides DcSpacingDefault,
        LocalDcShapes provides DcShapeTokensDefault,
        LocalDcElevation provides DcElevationDefault,
        LocalDcMotion provides DcMotionDefault,
        LocalDcTypography provides DcTerrainTypeScale,
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = DcTypography,
            shapes = DcShapes,
            content = content,
        )
    }
}
