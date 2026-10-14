package fr.descentecanyon.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import fr.descentecanyon.app.ui.design.DcTheme

@Composable
fun DescenteCanyonTheme(darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    DcTheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColor,
        content = content,
    )
}
