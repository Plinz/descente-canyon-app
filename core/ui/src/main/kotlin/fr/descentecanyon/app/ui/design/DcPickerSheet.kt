package fr.descentecanyon.app.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import kotlinx.coroutines.launch

/** One line of a [DcPickerSheet]: a heading, or an option the user can pick. */
sealed interface DcPickerEntry<out T> {
    val key: String
}

/** Groups the options below it, like the blocks of the forum index. */
data class DcPickerHeading(override val key: String, val title: String) : DcPickerEntry<Nothing>

/**
 * [count] is shown on the right, [isNested] indents the option under the one above, and a non-null
 * [isExpanded] makes it a group that opens or closes its nested options instead of being picked.
 */
data class DcPickerOption<out T>(
    override val key: String,
    val value: T,
    val label: String,
    val count: Int? = null,
    val isSelected: Boolean = false,
    val isNested: Boolean = false,
    val isExpanded: Boolean? = null,
    val badge: ImageVector? = null,
    val badgeDescription: String? = null,
) : DcPickerEntry<T>

/**
 * A list to pick one value from, in a bottom sheet. Picking an option closes the sheet; a group
 * option calls [onToggle] and stays open. The search field is shown when [query] is not null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DcPickerSheet(
    title: String,
    entries: List<DcPickerEntry<T>>,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onToggle: (T) -> Unit = {},
    query: String? = null,
    onQueryChange: (String) -> Unit = {},
    searchPlaceholder: String = "",
    emptyText: String? = null,
) {
    val colors = LocalDcColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // The sheet never covers the whole screen: the list scrolls instead, under the title and search.
    val listMaxHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.6f).toDp() }
    // Opens on the current choice, with the line above it (often its heading) in view.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (entries.indexOfFirst { it is DcPickerOption && it.isSelected } - 1).coerceAtLeast(0),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        containerColor = colors.backgroundElevated,
        contentColor = colors.textPrimary,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.textMuted.copy(alpha = 0.4f)) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
            if (query != null) {
                DcPickerSearchField(query = query, onQueryChange = onQueryChange, placeholder = searchPlaceholder)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = listMaxHeight),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(entries, key = { it.key }, contentType = { it is DcPickerHeading }) { entry ->
                    when (entry) {
                        is DcPickerHeading -> DcPickerHeadingRow(entry.title)

                        is DcPickerOption -> DcPickerOptionRow(
                            option = entry,
                            onClick = {
                                if (entry.isExpanded != null) {
                                    onToggle(entry.value)
                                } else {
                                    onPick(entry.value)
                                    scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
                                }
                            },
                        )
                    }
                }
                if (emptyText != null) {
                    item(key = "empty") {
                        Text(
                            text = emptyText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DcPickerSearchField(query: String, onQueryChange: (String) -> Unit, placeholder: String) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingIcon = if (query.isEmpty()) {
            null
        } else {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.search_clear_query))
                }
            }
        },
        singleLine = true,
        shape = CircleShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

@Composable
private fun DcPickerHeadingRow(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun DcPickerOptionRow(option: DcPickerOption<*>, onClick: () -> Unit) {
    val colors = LocalDcColors.current
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (option.isNested) 20.dp else 0.dp)
            .semantics { selected = option.isSelected },
        shape = LocalDcShapes.current.md,
        color = if (option.isSelected) colors.water.copy(alpha = 0.14f) else Color.Transparent,
        contentColor = if (option.isSelected) accent else colors.textPrimary,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = option.label,
                modifier = Modifier.weight(1f),
                style = if (option.isNested) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = if (option.isSelected || option.isExpanded != null) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            option.badge?.let { badge ->
                Icon(badge, contentDescription = option.badgeDescription, tint = accent, modifier = Modifier.size(18.dp))
            }
            option.count?.let { count ->
                Surface(
                    shape = LocalDcShapes.current.pill,
                    color = if (option.isSelected) accent.copy(alpha = 0.16f) else colors.surfaceRaised,
                    contentColor = if (option.isSelected) accent else colors.textSecondary,
                ) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            when {
                option.isExpanded != null -> Icon(
                    imageVector = if (option.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = colors.textMuted,
                )

                option.isSelected -> Icon(Icons.Default.Check, contentDescription = null, tint = accent)

                else -> Spacer(modifier = Modifier.size(24.dp))
            }
        }
    }
}
