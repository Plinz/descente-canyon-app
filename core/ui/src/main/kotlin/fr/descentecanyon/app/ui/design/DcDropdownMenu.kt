package fr.descentecanyon.app.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A short menu under its button (actions, or a few values), on the surface of the cards. */
@Composable
fun DcDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDcColors.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = LocalDcShapes.current.md,
        containerColor = colors.surfaceBase,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, colors.borderSubtle),
        content = content,
    )
}

/** [iconTint] defaults to the accent color; a selected item is in the accent color with a check. */
@Composable
fun DcDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    isSelected: Boolean = false,
) {
    val colors = LocalDcColors.current
    val accent = MaterialTheme.colorScheme.primary
    DropdownMenuItem(
        text = {
            Column {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected || supportingText != null) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) accent else colors.textPrimary,
                )
                if (supportingText != null) {
                    Text(supportingText, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
            }
        },
        onClick = onClick,
        modifier = modifier.semantics { selected = isSelected },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, tint = iconTint ?: accent, modifier = Modifier.size(22.dp)) } },
        trailingIcon = if (isSelected) {
            { Icon(Icons.Default.Check, contentDescription = null, tint = accent) }
        } else {
            null
        },
    )
}
