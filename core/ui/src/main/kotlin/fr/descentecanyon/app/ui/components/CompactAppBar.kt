package fr.descentecanyon.app.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import fr.descentecanyon.app.ui.design.DcTopBar

@Composable
fun CompactAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    DcTopBar(
        title = title,
        modifier = modifier,
        navigation = navigation,
        actions = actions,
    )
}
