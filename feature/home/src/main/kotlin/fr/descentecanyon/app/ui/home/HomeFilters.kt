package fr.descentecanyon.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.ForumCategory
import fr.descentecanyon.app.ui.design.DcPickerEntry
import fr.descentecanyon.app.ui.design.DcPickerHeading
import fr.descentecanyon.app.ui.design.DcPickerOption
import fr.descentecanyon.app.ui.design.DcPickerSheet
import fr.descentecanyon.app.ui.design.DcSelectField
import fr.descentecanyon.app.ui.design.DcZonePickerSheet
import fr.descentecanyon.app.ui.design.zoneFieldValue

/** The area of the latest reports: one field, and a sheet listing each country with its subdivisions. */
@Composable
internal fun HomeDebitZoneFilter(
    filterState: HomeDebitGeoFilterState,
    onZoneSelected: (country: String?, subdivision: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    DcSelectField(
        icon = Icons.Default.Place,
        label = stringResource(R.string.zone_filter_title),
        value = zoneFieldValue(filterState.selectedCountry, filterState.selectedDepartment),
        isActive = filterState.hasActiveFilter(),
        enabled = filterState.countries.isNotEmpty() || filterState.hasActiveFilter(),
        onClick = { showPicker = true },
        onClear = { onZoneSelected(null, null) },
        modifier = modifier,
    )

    if (showPicker) {
        DcZonePickerSheet(
            zones = filterState.countries,
            totalCount = filterState.totalCount,
            selectedCountry = filterState.selectedCountry,
            selectedSubdivision = filterState.selectedDepartment,
            subtitle = stringResource(R.string.home_zone_filter_hint),
            onZoneSelected = onZoneSelected,
            onDismiss = { showPicker = false },
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
        DcSelectField(
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
