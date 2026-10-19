package fr.descentecanyon.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.SearchSortField
import fr.descentecanyon.app.domain.model.SortDirection
import fr.descentecanyon.app.ui.design.DcPickerOption
import fr.descentecanyon.app.ui.design.DcPickerSheet
import fr.descentecanyon.app.ui.design.LocalDcColors

/** The sort fields, and above them the order of the current one (relevance has none). */
@Composable
internal fun SearchSortSheet(
    field: SearchSortField,
    direction: SortDirection,
    onFieldSelected: (SearchSortField) -> Unit,
    onDirectionSelected: (SortDirection) -> Unit,
    onDismiss: () -> Unit,
) {
    DcPickerSheet(
        title = stringResource(R.string.search_sort_title),
        entries = SearchSortField.entries.map { option ->
            DcPickerOption(key = option.name, value = option, label = sortLabel(option), icon = option.icon(), isSelected = option == field)
        },
        onPick = onFieldSelected,
        onDismiss = onDismiss,
        header = if (field == SearchSortField.RELEVANCE) {
            null
        } else {
            { SortDirectionRow(field = field, direction = direction, onDirectionSelected = onDirectionSelected) }
        },
    )
}

@Composable
private fun SortDirectionRow(field: SearchSortField, direction: SortDirection, onDirectionSelected: (SortDirection) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.search_sort_order),
            style = MaterialTheme.typography.labelLarge,
            color = LocalDcColors.current.textMuted,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val options = listOf(SortDirection.ASC, SortDirection.DESC)
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = direction == option,
                    onClick = { onDirectionSelected(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    modifier = Modifier.weight(1f),
                    icon = {
                        Icon(
                            imageVector = if (option == SortDirection.ASC) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    label = { Text(directionLabel(field, option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

/** Said the way the field reads: "A → Z" for names, "nearest first" for distances. */
@Composable
private fun directionLabel(field: SearchSortField, direction: SortDirection): String = when (field) {
    SearchSortField.NAME -> if (direction == SortDirection.ASC) "A → Z" else "Z → A"

    SearchSortField.DISTANCE -> if (direction == SortDirection.ASC) {
        stringResource(R.string.search_sort_nearest_first)
    } else {
        stringResource(R.string.search_sort_farthest_first)
    }

    else -> if (direction ==
        SortDirection.ASC
    ) {
        stringResource(R.string.search_sort_ascending)
    } else {
        stringResource(R.string.search_sort_descending)
    }
}

private fun SearchSortField.icon(): ImageVector = when (this) {
    SearchSortField.RELEVANCE -> Icons.AutoMirrored.Filled.ManageSearch
    SearchSortField.NAME -> Icons.Default.SortByAlpha
    SearchSortField.INTEREST -> Icons.Default.Star
    SearchSortField.POPULARITY -> Icons.Default.Groups
    SearchSortField.DIFFICULTY -> Icons.Default.Speed
    SearchSortField.ELEVATION -> Icons.Default.Height
    SearchSortField.LENGTH -> Icons.Default.Straighten
    SearchSortField.MAX_WATERFALL -> Icons.Default.Waves
    SearchSortField.DISTANCE -> Icons.Default.NearMe
}
