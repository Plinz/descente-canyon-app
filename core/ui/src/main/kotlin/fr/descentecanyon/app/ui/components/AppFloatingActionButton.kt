package fr.descentecanyon.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.ui.design.DcFloatingActionButton

@Composable
fun AppFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 64.dp,
    iconSize: Dp = 28.dp,
    icon: @Composable (Modifier) -> Unit,
) {
    DcFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        buttonSize = buttonSize,
        iconSize = iconSize,
        icon = icon,
    )
}
