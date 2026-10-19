package fr.descentecanyon.app.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R

/**
 * The field that opens a picker (a [DcPickerSheet] or a [DcDropdownMenu]): a pill like the search
 * bars, with the label above the chosen value. When [isActive] and [onClear] is given, a cross
 * clears the choice.
 */
@Composable
fun DcSelectField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isActive: Boolean = false,
    enabled: Boolean = true,
    onClear: (() -> Unit)? = null,
) {
    val colors = LocalDcColors.current
    val accent = MaterialTheme.colorScheme.primary
    val showClear = isActive && onClear != null
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        shape = CircleShape,
        color = if (isActive) colors.water.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (isActive) accent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(start = if (icon != null) 16.dp else 20.dp, end = if (showClear) 4.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = if (enabled) accent else colors.textMuted)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            ) {
                Text(text = label, style = MaterialTheme.typography.labelMedium, color = colors.textMuted, maxLines = 1)
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (enabled) colors.textPrimary else colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showClear) {
                IconButton(onClick = { onClear?.invoke() }, modifier = Modifier.excludeFromBackGesture()) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.search_clear_filters))
                }
            } else {
                Icon(Icons.Default.ExpandMore, contentDescription = null, tint = colors.textMuted)
            }
        }
    }
}
