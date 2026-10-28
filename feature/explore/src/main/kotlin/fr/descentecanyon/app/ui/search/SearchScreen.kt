package fr.descentecanyon.app.ui.search

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.DriveTimeSource
import fr.descentecanyon.app.domain.model.SearchSortField
import fr.descentecanyon.app.domain.model.SortDirection
import fr.descentecanyon.app.domain.model.toSummary
import fr.descentecanyon.app.map.MAP_SEARCH_STYLE_URI
import fr.descentecanyon.app.perf.PerformanceTrace
import fr.descentecanyon.app.ui.common.asString
import fr.descentecanyon.app.ui.components.AppFloatingActionButton
import fr.descentecanyon.app.ui.components.CanyonSummaryCard
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.components.SelectedCanyonSheetContent
import fr.descentecanyon.app.ui.components.formatDurationMinutes
import fr.descentecanyon.app.ui.components.longDayLabel
import fr.descentecanyon.app.ui.components.shortDayLabel
import fr.descentecanyon.app.ui.design.DcSectionHeader
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.LocalDcShapes
import fr.descentecanyon.app.ui.design.LocalDcSpacing
import fr.descentecanyon.app.ui.design.excludeFromBackGesture
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.design.rememberDcScreenHorizontalPadding
import fr.descentecanyon.app.ui.location.hasLocationPermission
import fr.descentecanyon.app.ui.location.loadCurrentDeviceLocation
import fr.descentecanyon.app.ui.location.requestLocationSettings
import fr.descentecanyon.app.ui.map.MapLibreView
import fr.descentecanyon.app.ui.test.TestTags

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onCanyonClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Opened for a day of a stay: back to it. */
    onBackClick: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val stayPick by viewModel.stayPick.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var showFiltersSheet by rememberSaveable { mutableStateOf(false) }
    var showSortMenu by rememberSaveable { mutableStateOf(false) }
    var pendingDistanceSort by rememberSaveable { mutableStateOf(false) }
    var lastHandledScrollResetId by rememberSaveable { mutableIntStateOf(uiState.scrollResetRequestId) }

    LaunchedEffect(Unit) {
        PerformanceTrace.logEvent("search_screen_visible")
    }

    fun startLocationLookup() {
        viewModel.onLocationLookupStarted()
        loadCurrentDeviceLocation(
            context = context,
            onLocation = { latitude, longitude ->
                viewModel.onUserLocationUpdated(latitude, longitude)
                if (pendingDistanceSort) {
                    viewModel.onSortSelected(SearchSortField.DISTANCE)
                    pendingDistanceSort = false
                }
            },
            onUnavailable = {
                viewModel.onLocationUnavailable()
                pendingDistanceSort = false
            },
        )
    }

    val locationSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startLocationLookup()
        } else {
            viewModel.onLocationUnavailable()
            pendingDistanceSort = false
        }
    }

    fun requestLocationWithSettingsCheck() {
        requestLocationSettings(
            context = context,
            onEnabled = ::startLocationLookup,
            onResolutionRequired = { request -> locationSettingsLauncher.launch(request) },
            onUnavailable = {
                viewModel.onLocationUnavailable()
                pendingDistanceSort = false
            },
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.onLocationPermissionResult(granted)
        if (granted) {
            requestLocationWithSettingsCheck()
        } else {
            pendingDistanceSort = false
        }
    }

    fun requestCurrentLocation(applyDistanceSortWhenReady: Boolean) {
        if (applyDistanceSortWhenReady) pendingDistanceSort = true

        if (!context.hasLocationPermission()) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            return
        }

        viewModel.onLocationPermissionResult(true)
        requestLocationWithSettingsCheck()
    }

    LaunchedEffect(Unit) {
        if (context.hasLocationPermission()) {
            viewModel.onLocationPermissionResult(true)
        }
    }

    LaunchedEffect(uiState.scrollResetRequestId) {
        if (uiState.scrollResetRequestId > lastHandledScrollResetId) {
            listState.scrollToItem(0)
            lastHandledScrollResetId = uiState.scrollResetRequestId
        }
    }

    val activeFilters = buildActiveFilterActions(uiState, viewModel)
    val dcColors = LocalDcColors.current
    val dcShapes = LocalDcShapes.current
    val spacing = LocalDcSpacing.current
    val contentWidth = rememberDcContentWidth()
    val screenHorizontalPadding = rememberDcScreenHorizontalPadding()

    Column(modifier = modifier.fillMaxSize().background(dcColors.backgroundBase)) {
        // For a day of a stay: a screen of its own, back to the stay; else the tab, under the status bar.
        if (viewModel.isStayPick) {
            CompactAppBar(
                title = stringResource(if ((stayPick?.outingId ?: 0) > 0) R.string.stay_outing_change else R.string.stay_choose_canyon),
                navigation = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(if (viewModel.isStayPick) Modifier else Modifier.statusBarsPadding()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(contentWidth)
                    .align(Alignment.TopCenter)
                    .padding(horizontal = screenHorizontalPadding),
            ) {
                Spacer(modifier = Modifier.height(spacing.sm))

                stayPick?.let { pick ->
                    Text(
                        stringResource(R.string.stay_picker_subtitle, longDayLabel(pick.date), pick.place.name),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = spacing.sm).testTag(TestTags.SEARCH_STAY_DAY),
                    )
                }

                OutlinedTextField(
                    value = uiState.queryDraft,
                    onValueChange = viewModel::onQueryChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.SEARCH_QUERY_FIELD),
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        when {
                            uiState.queryDraft.isNotEmpty() -> {
                                IconButton(onClick = viewModel::clearQuery, modifier = Modifier.excludeFromBackGesture()) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.search_clear_query))
                                }
                            }

                            uiState.isSearching -> {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        }
                    },
                    singleLine = true,
                    shape = dcShapes.xl,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = dcColors.surfaceBase,
                        unfocusedContainerColor = dcColors.surfaceBase,
                        disabledContainerColor = dcColors.surfaceRaised,
                        focusedIndicatorColor = dcColors.primaryAction,
                        unfocusedIndicatorColor = dcColors.borderSubtle,
                        focusedLeadingIconColor = dcColors.primaryAction,
                        unfocusedLeadingIconColor = dcColors.textMuted,
                    ),
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SearchControlButton(
                        title = stringResource(R.string.search_filters),
                        value = if (uiState.activeFilterCount > 0) {
                            pluralStringResource(
                                R.plurals.search_filters_active_count,
                                uiState.activeFilterCount,
                                uiState.activeFilterCount,
                            )
                        } else {
                            stringResource(R.string.search_filters_none)
                        },
                        leadingIcon = Icons.Default.Tune,
                        active = uiState.activeFilterCount > 0,
                        onClick = { showFiltersSheet = true },
                        modifier = Modifier.weight(1f),
                    )

                    SearchControlButton(
                        title = stringResource(R.string.search_sort_title),
                        value = sortLabel(uiState.criteria.sortField),
                        leadingIcon = Icons.AutoMirrored.Filled.Sort,
                        trailingIcon = when {
                            uiState.criteria.sortField == SearchSortField.RELEVANCE -> Icons.Default.ExpandMore
                            uiState.criteria.sortDirection == SortDirection.ASC -> Icons.Default.ArrowUpward
                            else -> Icons.Default.ArrowDownward
                        },
                        onClick = { showSortMenu = true },
                        modifier = Modifier.weight(1f),
                    )
                }

                if (activeFilters.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        activeFilters.forEach { filter ->
                            FilterChip(
                                selected = true,
                                onClick = filter.onRemove,
                                label = { Text(filter.label) },
                                trailingIcon = { Icon(Icons.Default.Clear, contentDescription = null) },
                            )
                        }
                        TextButton(onClick = viewModel::clearAllFilters) {
                            Text(stringResource(R.string.search_clear_filters))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // Without query nor filter nothing is listed: the count is the catalog size, not results.
                        text = if (uiState.isResultListDeferred) {
                            pluralStringResource(R.plurals.search_catalog_count, uiState.totalResultsCount, uiState.totalResultsCount)
                        } else {
                            pluralStringResource(R.plurals.search_results_count, uiState.totalResultsCount, uiState.totalResultsCount)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (uiState.isLoading) {
                    SearchCatalogLoadingHint(
                        hasTypedQuery = uiState.queryDraft.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                uiState.error?.let { error ->
                    Text(
                        text = error.asString(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                if (!uiState.isLoading && uiState.isResultListDeferred) {
                    SearchIdleContent(
                        recentCanyons = uiState.recentCanyons,
                        descendedIds = uiState.descendedIds,
                        onCanyonClick = onCanyonClick,
                        onClearRecentCanyons = viewModel::clearRecentCanyons,
                        bottomPadding = contentPadding.calculateBottomPadding() + 96.dp,
                        modifier = Modifier.weight(1f),
                    )
                } else if (!uiState.isLoading && uiState.results.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.no_results),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (uiState.resultViewMode == SearchResultViewMode.LIST) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(uiState.results, key = { it.id }) { canyon ->
                            CanyonSummaryCard(
                                canyon = canyon.toSummary(),
                                onClick = { onCanyonClick(canyon.id) },
                                isDescended = canyon.isDescended,
                                footer = stayPick?.let { pick -> { StayPickFooter(pick, canyon) } },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(96.dp)) }
                    }
                } else {
                    val markers = remember(uiState.results) {
                        uiState.results.mapNotNull { item ->
                            item.takeIf { it.representativeLat != null && it.representativeLng != null }?.toSummary()
                        }
                    }
                    if (markers.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(28.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.search_map_no_coordinates),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            shape = dcShapes.xl,
                            colors = CardDefaults.cardColors(containerColor = dcColors.surfaceRaised),
                            border = BorderStroke(1.dp, dcColors.borderSubtle),
                        ) {
                            MapLibreView(
                                markers = markers,
                                userLatitude = uiState.userLatitude,
                                userLongitude = uiState.userLongitude,
                                onMarkerClick = viewModel::selectCanyon,
                                styleUri = MAP_SEARCH_STYLE_URI,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = contentPadding.calculateBottomPadding() + 8.dp),
                            )
                        }
                    }
                }
            }

            AppFloatingActionButton(
                onClick = {
                    val next = if (uiState.resultViewMode ==
                        SearchResultViewMode.MAP
                    ) {
                        SearchResultViewMode.LIST
                    } else {
                        SearchResultViewMode.MAP
                    }
                    viewModel.onResultViewModeChanged(next)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = contentPadding.calculateBottomPadding() + 20.dp),
                icon = { iconModifier ->
                    if (uiState.resultViewMode == SearchResultViewMode.MAP) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(R.string.search_show_list),
                            modifier = iconModifier,
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.map_search_24),
                            contentDescription = stringResource(R.string.search_show_map),
                            modifier = iconModifier,
                        )
                    }
                },
            )
        }
    }

    if (showFiltersSheet) {
        SearchFiltersSheet(
            uiState = SearchFiltersSheetState(
                criteria = uiState.criteria,
                zones = uiState.zones,
                totalResultsCount = uiState.totalResultsCount,
            ),
            onDismiss = { showFiltersSheet = false },
            onCriteriaChanged = viewModel::onCriteriaChanged,
            onClearAll = viewModel::clearAllFilters,
        )
    }

    if (showSortMenu) {
        SearchSortSheet(
            field = uiState.criteria.sortField,
            direction = uiState.criteria.sortDirection,
            onFieldSelected = { field ->
                // For a stay, distances are from its place: the position of the user is not asked.
                if (field == SearchSortField.DISTANCE && !viewModel.isStayPick) {
                    requestCurrentLocation(applyDistanceSortWhenReady = true)
                } else {
                    pendingDistanceSort = false
                    viewModel.onSortSelected(field)
                }
            },
            onDirectionSelected = viewModel::onSortDirectionSelected,
            onDismiss = { showSortMenu = false },
        )
    }

    uiState.selectedCanyon?.let { selectedCanyon ->
        ModalBottomSheet(
            onDismissRequest = viewModel::clearSelectedCanyon,
            containerColor = LocalDcColors.current.backgroundElevated,
            contentColor = LocalDcColors.current.textPrimary,
            shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
            dragHandle = { BottomSheetDefaults.DragHandle(color = LocalDcColors.current.textMuted.copy(alpha = 0.4f)) },
        ) {
            SelectedCanyonSheetContent(
                canyon = selectedCanyon.toSummary(),
                onOpen = {
                    viewModel.clearSelectedCanyon()
                    onCanyonClick(selectedCanyon.id)
                },
                onClose = viewModel::clearSelectedCanyon,
            )
        }
    }
}

@Composable
private fun SearchControlButton(
    title: String,
    value: String,
    leadingIcon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: ImageVector = Icons.Default.ExpandMore,
    active: Boolean = false,
) {
    val dcColors = LocalDcColors.current
    val shapes = LocalDcShapes.current
    Surface(
        onClick = onClick,
        modifier = modifier.height(62.dp),
        shape = shapes.lg,
        color = if (active) dcColors.water.copy(alpha = 0.16f) else dcColors.surfaceBase,
        contentColor = dcColors.textPrimary,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(
            width = 1.dp,
            color = if (active) dcColors.primaryAction.copy(alpha = 0.5f) else dcColors.borderSubtle,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = if (active) dcColors.primaryAction else dcColors.textMuted,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = dcColors.textMuted,
                    maxLines = 1,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    color = dcColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (active) dcColors.primaryAction else dcColors.textMuted,
            )
        }
    }
}

@Composable
private fun SearchCatalogLoadingHint(hasTypedQuery: Boolean, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = if (hasTypedQuery) {
                    stringResource(R.string.search_catalog_loading_with_query)
                } else {
                    stringResource(R.string.search_catalog_loading_idle)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Before any query or filter: the canyons opened recently, available offline. */
@Composable
private fun SearchIdleContent(
    recentCanyons: List<CanyonSummary>,
    descendedIds: Set<Int>,
    onCanyonClick: (Int) -> Unit,
    onClearRecentCanyons: () -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.search_broad_results_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (recentCanyons.isNotEmpty()) {
            item {
                DcSectionHeader(
                    title = stringResource(R.string.search_recent_title),
                    action = {
                        TextButton(onClick = onClearRecentCanyons) {
                            Text(stringResource(R.string.search_recent_clear))
                        }
                    },
                )
            }
            items(recentCanyons, key = { "recent-${it.id}" }) { canyon ->
                CanyonSummaryCard(canyon = canyon, onClick = { onCanyonClick(canyon.id) }, isDescended = canyon.id in descendedIds)
            }
        }
    }
}

/** Under a canyon listed for a day of a stay: the drive from its place, and the other day of the stay it is planned for. */
@Composable
private fun StayPickFooter(pick: SearchStayPick, canyon: CanyonSearchItem) {
    val colors = LocalDcColors.current
    pick.driveTo(canyon)?.let { drive ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
            Text(
                stringResource(
                    if (drive.source == DriveTimeSource.ESTIMATE) R.string.stay_drive_estimate else R.string.stay_drive,
                    formatDurationMinutes(drive.minutes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
    pick.plannedOn[canyon.id]?.let { day ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.EventAvailable, contentDescription = null, tint = colors.warning, modifier = Modifier.size(16.dp))
            Text(
                stringResource(R.string.stay_picker_planned, shortDayLabel(day)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}
