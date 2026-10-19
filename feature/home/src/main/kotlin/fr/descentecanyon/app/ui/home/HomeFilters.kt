package fr.descentecanyon.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.ForumCategory
import fr.descentecanyon.app.ui.design.DcPickerEntry
import fr.descentecanyon.app.ui.design.DcPickerHeading
import fr.descentecanyon.app.ui.design.DcPickerOption
import fr.descentecanyon.app.ui.design.DcPickerSheet
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.excludeFromBackGesture
import java.text.Normalizer

/** The area of the latest reports: one field, and a sheet listing each country with its subdivisions. */
@Composable
internal fun HomeDebitZoneFilter(
    filterState: HomeDebitGeoFilterState,
    onZoneSelected: (country: String?, subdivision: String?) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val country = filterState.selectedCountry
    HomeFilterField(
        icon = Icons.Default.Place,
        label = stringResource(R.string.home_zone_filter_title),
        value = when {
            country == null -> stringResource(R.string.home_zone_filter_all)
            filterState.selectedDepartment != null -> "${filterState.selectedDepartment}, $country"
            else -> country
        },
        isActive = filterState.hasActiveFilter(),
        enabled = filterState.countries.isNotEmpty() || filterState.hasActiveFilter(),
        onClick = { showPicker = true },
        onClear = onClear,
        modifier = modifier,
    )

    if (showPicker) {
        var query by remember { mutableStateOf("") }
        // The chosen country is open, and the only one when there is a single country.
        var expandedCountries by remember {
            mutableStateOf(setOfNotNull(country, filterState.countries.singleOrNull()?.name))
        }
        val entries = zonePickerEntries(
            filter = filterState,
            expandedCountries = expandedCountries,
            query = query,
            everywhereLabel = stringResource(R.string.home_zone_filter_all),
            wholeCountryLabel = stringResource(R.string.home_zone_filter_whole_country),
        )
        DcPickerSheet(
            title = stringResource(R.string.home_zone_filter_title),
            subtitle = stringResource(R.string.home_zone_filter_hint),
            entries = entries,
            onPick = { pick ->
                when (pick) {
                    ZonePick.Everywhere -> onClear()
                    is ZonePick.Country -> onZoneSelected(pick.country, null)
                    is ZonePick.Subdivision -> onZoneSelected(pick.country, pick.subdivision)
                    is ZonePick.Toggle -> Unit
                }
            },
            onToggle = { pick ->
                if (pick is ZonePick.Toggle) {
                    expandedCountries = if (pick.country in expandedCountries) {
                        expandedCountries - pick.country
                    } else {
                        expandedCountries + pick.country
                    }
                }
            },
            onDismiss = { showPicker = false },
            query = query,
            onQueryChange = { query = it },
            searchPlaceholder = stringResource(R.string.home_zone_filter_search),
            emptyText = stringResource(R.string.search_filter_option_no_results).takeIf { entries.isEmpty() },
        )
    }
}

/** The forum section of the active topics, listed as on the forum index, and following it. */
@Composable
internal fun HomeForumCategoryFilter(
    filterState: HomeForumFilterState,
    followedCategoryKeys: Set<String>,
    onCategorySelected: (String?) -> Unit,
    onToggleCategoryFollow: (ForumCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val selectedCategory = filterState.selectedCategory()
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeFilterField(
            icon = Icons.Default.FolderSpecial,
            label = stringResource(R.string.notifications_forum_filter_title),
            value = selectedCategory?.forumName ?: stringResource(R.string.notifications_forum_filter_all),
            isActive = filterState.hasActiveFilter(),
            enabled = filterState.availableCategories.isNotEmpty(),
            onClick = { showPicker = true },
            onClear = { onCategorySelected(null) },
        )
        if (selectedCategory != null) {
            val isFollowed = selectedCategory.key() in followedCategoryKeys
            FilterChip(
                selected = isFollowed,
                onClick = { onToggleCategoryFollow(selectedCategory) },
                label = {
                    Text(
                        if (isFollowed) {
                            stringResource(R.string.notifications_forum_category_badge)
                        } else {
                            stringResource(R.string.notifications_forum_follow_action)
                        },
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (isFollowed) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                shape = CircleShape,
            )
        }
    }

    if (showPicker) {
        DcPickerSheet(
            title = stringResource(R.string.notifications_forum_filter_title),
            subtitle = stringResource(R.string.home_forum_filter_hint).takeIf { filterState.totalTopicCount > 0 },
            entries = forumPickerEntries(
                filter = filterState,
                followedCategoryKeys = followedCategoryKeys,
                allLabel = stringResource(R.string.notifications_forum_filter_all),
                followedLabel = stringResource(R.string.notifications_forum_category_badge),
            ),
            onPick = { category -> onCategorySelected(category?.key()) },
            onDismiss = { showPicker = false },
        )
    }
}

/** Looks like the search bar above it; the cross clears the filter. */
@Composable
private fun HomeFilterField(
    icon: ImageVector,
    label: String,
    value: String,
    isActive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDcColors.current
    val accent = MaterialTheme.colorScheme.primary
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
            modifier = Modifier.padding(start = 16.dp, end = if (isActive) 4.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = if (enabled) accent else colors.textMuted)
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
            if (isActive) {
                IconButton(onClick = onClear, modifier = Modifier.excludeFromBackGesture()) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.search_clear_filters))
                }
            } else {
                Icon(Icons.Default.ExpandMore, contentDescription = null, tint = colors.textMuted)
            }
        }
    }
}

/** What a line of the area picker does. */
internal sealed interface ZonePick {
    data object Everywhere : ZonePick

    data class Toggle(val country: String) : ZonePick

    data class Country(val country: String) : ZonePick

    data class Subdivision(val country: String, val subdivision: String) : ZonePick
}

/**
 * Countries are groups that open on their subdivisions, under a "whole country" line. While
 * searching, every country with a match is open and shows only the matching lines.
 */
internal fun zonePickerEntries(
    filter: HomeDebitGeoFilterState,
    expandedCountries: Set<String>,
    query: String,
    everywhereLabel: String,
    wholeCountryLabel: String,
): List<DcPickerEntry<ZonePick>> {
    val searching = query.isNotBlank()
    return buildList {
        if (!searching) {
            add(
                DcPickerOption(
                    key = "all",
                    value = ZonePick.Everywhere,
                    label = everywhereLabel,
                    count = filter.totalCount,
                    isSelected = !filter.hasActiveFilter(),
                ),
            )
        }
        filter.countries.forEach { country ->
            val countryMatches = !searching || country.name.matchesPickerQuery(query)
            val subdivisions = if (searching) country.subdivisions.filter { it.name.matchesPickerQuery(query) } else country.subdivisions
            if (!countryMatches && subdivisions.isEmpty()) return@forEach
            val isSelectedCountry = filter.selectedCountry.equals(country.name, ignoreCase = true)
            val isExpanded = searching || country.name in expandedCountries
            add(
                DcPickerOption(
                    key = "country:${country.name}",
                    value = ZonePick.Toggle(country.name),
                    label = country.name,
                    count = country.reportCount,
                    isSelected = isSelectedCountry && !isExpanded,
                    isExpanded = isExpanded,
                ),
            )
            if (!isExpanded) return@forEach
            if (countryMatches) {
                add(
                    DcPickerOption(
                        key = "country-all:${country.name}",
                        value = ZonePick.Country(country.name),
                        label = wholeCountryLabel,
                        count = country.reportCount,
                        isSelected = isSelectedCountry && filter.selectedDepartment == null,
                        isNested = true,
                    ),
                )
            }
            subdivisions.forEach { subdivision ->
                add(
                    DcPickerOption(
                        key = "subdivision:${country.name}/${subdivision.name}",
                        value = ZonePick.Subdivision(country.name, subdivision.name),
                        label = subdivision.name,
                        count = subdivision.reportCount,
                        isSelected = isSelectedCountry && subdivision.name.equals(filter.selectedDepartment, ignoreCase = true),
                        isNested = true,
                    ),
                )
            }
        }
    }
}

/** The forums under the headings of the forum index; the count is the number of active topics in each. */
internal fun forumPickerEntries(
    filter: HomeForumFilterState,
    followedCategoryKeys: Set<String>,
    allLabel: String,
    followedLabel: String,
): List<DcPickerEntry<ForumCategory?>> = buildList {
    add(
        DcPickerOption(
            key = "all",
            value = null,
            label = allLabel,
            count = filter.totalTopicCount.takeIf { it > 0 },
            isSelected = filter.selectedCategoryKey == null,
        ),
    )
    var section: String? = null
    filter.availableCategories.forEachIndexed { index, category ->
        val categorySection = category.section
        if (categorySection != null && categorySection != section) add(DcPickerHeading(key = "section:$index", title = categorySection))
        section = categorySection
        val key = category.key()
        val isFollowed = key in followedCategoryKeys
        add(
            DcPickerOption(
                key = "forum:$key",
                value = category,
                label = category.forumName,
                count = filter.topicCounts[key],
                isSelected = key == filter.selectedCategoryKey,
                badge = Icons.Default.NotificationsActive.takeIf { isFollowed },
                badgeDescription = followedLabel.takeIf { isFollowed },
            ),
        )
    }
}

private fun String.matchesPickerQuery(query: String): Boolean = normalizedPickerText().contains(query.normalizedPickerText())

/** "alpes mar" finds "Alpes-Maritimes", "ariege" finds "Ariège". */
private fun String.normalizedPickerText(): String = Normalizer
    .normalize(this, Normalizer.Form.NFD)
    .replace(DiacriticsRegex, "")
    .lowercase()
    .replace(SeparatorsRegex, " ")
    .trim()

private val DiacriticsRegex = Regex("\\p{Mn}+")
private val SeparatorsRegex = Regex("[^\\p{L}\\p{N}]+")
