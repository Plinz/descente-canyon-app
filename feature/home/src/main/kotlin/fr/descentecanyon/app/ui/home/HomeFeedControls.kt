package fr.descentecanyon.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.ForumCategory
import fr.descentecanyon.app.domain.model.HomeFeedType
import fr.descentecanyon.app.domain.model.forumCategoryKey
import java.text.Normalizer

@Composable
internal fun HomeForumFilterControls(
    filterState: HomeForumFilterState,
    onCategorySelected: (String?) -> Unit,
    onClear: () -> Unit,
    followedCategoryKeys: Set<String>,
    onToggleCategoryFollow: (ForumCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeStringPickerField(
                    label = stringResource(R.string.notifications_forum_filter_title),
                    selected = filterState.availableCategories.firstOrNull { category ->
                        forumCategoryKey(category.forumId, category.forumName) == filterState.selectedCategoryKey
                    }?.forumName,
                    emptyLabel = stringResource(R.string.notifications_forum_filter_all),
                    options = filterState.availableCategories.map { it.forumName },
                    enabled = filterState.availableCategories.isNotEmpty(),
                    onSelected = { selectedName ->
                        val selectedKey = filterState.availableCategories.firstOrNull { it.forumName == selectedName }
                            ?.let { forumCategoryKey(it.forumId, it.forumName) }
                        onCategorySelected(selectedKey)
                    },
                    modifier = Modifier.weight(1f),
                )
                if (filterState.hasActiveFilter()) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.search_clear_filters))
                    }
                }
            }

            filterState.selectedCategoryKey
                ?.let { selectedKey ->
                    filterState.availableCategories.firstOrNull {
                        forumCategoryKey(it.forumId, it.forumName) ==
                            selectedKey
                    }
                }
                ?.let { selectedCategory ->
                    OutlinedButton(onClick = { onToggleCategoryFollow(selectedCategory) }) {
                        Text(
                            if (forumCategoryKey(selectedCategory.forumId, selectedCategory.forumName) in followedCategoryKeys) {
                                stringResource(R.string.notifications_forum_unfollow_action)
                            } else {
                                stringResource(R.string.notifications_forum_follow_action)
                            },
                        )
                    }
                }
        }
    }
}

@Composable
internal fun HomeFeedPicker(selectedFeed: HomeFeedType, onFeedSelected: (HomeFeedType) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(
        modifier = modifier.fillMaxWidth(),
    ) {
        val options = listOf(HomeFeedType.DEBITS, HomeFeedType.FORUM)
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selectedFeed == option,
                onClick = { onFeedSelected(option) },
                modifier = Modifier.weight(1f),
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = {
                    Text(
                        if (option == HomeFeedType.DEBITS) {
                            stringResource(R.string.home_feed_debits)
                        } else {
                            stringResource(R.string.home_feed_forum)
                        },
                    )
                },
            )
        }
    }
}

@Composable
internal fun HomeFeedHeader(selectedFeed: HomeFeedType, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (selectedFeed == HomeFeedType.DEBITS) {
                    stringResource(R.string.last_debits)
                } else {
                    stringResource(R.string.home_forum_title)
                },
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.refresh),
                )
            }
        }
        Text(
            text = if (selectedFeed == HomeFeedType.DEBITS) {
                stringResource(R.string.home_debits_subtitle)
            } else {
                stringResource(R.string.home_forum_subtitle)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun HomeDebitGeoFilterControls(
    filterState: HomeDebitGeoFilterState,
    onCountrySelected: (String?) -> Unit,
    onDepartmentSelected: (String?) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeStringPickerField(
                    label = stringResource(R.string.search_filter_country),
                    selected = filterState.selectedCountry,
                    emptyLabel = stringResource(R.string.search_filter_any_country),
                    options = filterState.availableCountries,
                    enabled = filterState.availableCountries.isNotEmpty(),
                    onSelected = onCountrySelected,
                    modifier = Modifier.weight(1f),
                )
                if (filterState.hasActiveFilter()) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.search_clear_filters))
                    }
                }
            }

            if (filterState.selectedCountry != null) {
                HomeStringPickerField(
                    label = stringResource(R.string.search_filter_department),
                    selected = filterState.selectedDepartment,
                    emptyLabel = stringResource(R.string.search_filter_any_department),
                    options = filterState.availableDepartments,
                    enabled = filterState.availableDepartments.isNotEmpty(),
                    onSelected = onDepartmentSelected,
                )
            }
        }
    }
}

@Composable
private fun HomeStringPickerField(
    label: String,
    selected: String?,
    emptyLabel: String,
    options: List<String>,
    enabled: Boolean,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val filteredOptions = remember(options, query) {
        if (query.isBlank()) options else options.filter { it.matchesPickerQuery(query) }
    }

    OutlinedButton(
        onClick = {
            query = ""
            showPicker = true
        },
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = selected ?: emptyLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.search_filter_option_search)) },
                        singleLine = true,
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                    ) {
                        item {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = emptyLabel,
                                        color = if (selected ==
                                            null
                                        ) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                },
                                onClick = {
                                    showPicker = false
                                    onSelected(null)
                                },
                            )
                        }
                        if (filteredOptions.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.search_filter_option_no_results),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                                )
                            }
                        } else {
                            items(filteredOptions) { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option,
                                            color = if (option ==
                                                selected
                                            ) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                    },
                                    onClick = {
                                        showPicker = false
                                        onSelected(option)
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

private fun String.matchesPickerQuery(query: String): Boolean = normalizedPickerText().contains(query.normalizedPickerText())

private fun String.normalizedPickerText(): String = Normalizer
    .normalize(this, Normalizer.Form.NFD)
    .replace(DiacriticsRegex, "")
    .lowercase()

private val DiacriticsRegex = Regex("\\p{Mn}+")
