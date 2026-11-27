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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import fr.descentecanyon.app.domain.model.SearchCriteria
import fr.descentecanyon.app.domain.model.SearchSortField
import fr.descentecanyon.app.domain.model.SortDirection
import fr.descentecanyon.app.domain.model.haversineKm
import fr.descentecanyon.app.domain.model.toSummary
import fr.descentecanyon.app.map.MAP_SEARCH_STYLE_URI
import fr.descentecanyon.app.perf.PerformanceTrace
import fr.descentecanyon.app.ui.common.asString
import fr.descentecanyon.app.ui.components.AppFloatingActionButton
import fr.descentecanyon.app.ui.components.CanyonCardInfo
import fr.descentecanyon.app.ui.components.CanyonSummaryCard
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.components.SelectedCanyonSheetContent
import fr.descentecanyon.app.ui.components.formatDurationMinutes
import fr.descentecanyon.app.ui.components.longDayLabel
import fr.descentecanyon.app.ui.design.DcDropdownMenu
import fr.descentecanyon.app.ui.design.DcDropdownMenuItem
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
import fr.descentecanyon.app.ui.map.ExploreMap
import fr.descentecanyon.app.ui.map.MapLibreView
import fr.descentecanyon.app.ui.test.TestTags
import kotlin.math.roundToInt

/**
 * Explorer: the canyons of the catalog by name, filters and sort, as a list or on a map that share
 * them. Also the search of a canyon for a day of a stay, around its place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onCanyonClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Opened for a day of a stay: back to it. */
    onBackClick: () -> Unit = {},
    /** A name found nowhere: proposing the canyon, with this name. */
    onProposeCanyon: ((String) -> Unit)? = null,
    /** Opened on its map (launcher shortcut, home): shown once, then [onOpenMapHandled]. */
    openMapRequested: Boolean = false,
    onOpenMapHandled: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val stayPick by viewModel.stayPick.collectAsStateWithLifecycle()
    val estimates by viewModel.estimates.collectAsStateWithLifecycle()
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
            // The canyons around the user, at once and without a prompt, when the position is allowed.
            if (!viewModel.isStayPick && uiState.userLatitude == null) {
                viewModel.onLocationLookupStarted()
                loadCurrentDeviceLocation(
                    context = context,
                    onLocation = viewModel::onUserLocationUpdated,
                    onUnavailable = viewModel::onLocationLookupEnded,
                )
            }
        }
    }

    LaunchedEffect(openMapRequested) {
        if (openMapRequested) {
            viewModel.onResultViewModeChanged(SearchResultViewMode.MAP)
            onOpenMapHandled()
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

    val modeToggle: @Composable () -> Unit = {
        ExploreModeToggle(mode = uiState.resultViewMode, onModeSelected = viewModel::onResultViewModeChanged)
    }

    Column(modifier = modifier.fillMaxSize().background(dcColors.backgroundBase)) {
        // A canyon chosen (a day of a stay, an outing): a screen of its own, with a way back; else the tab, under the status bar.
        if (viewModel.isPick) {
            CompactAppBar(
                title = stringResource(
                    when {
                        viewModel.isOutingPick -> R.string.outings_new_outing
                        (stayPick?.outingId ?: 0) > 0 -> R.string.stay_outing_change
                        else -> R.string.stay_choose_canyon
                    },
                ),
                navigation = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        }
        if (uiState.resultViewMode == SearchResultViewMode.MAP) {
            ExploreMap(
                criteria = uiState.criteria,
                onOpenFilters = { showFiltersSheet = true },
                onClearFilters = viewModel::clearAllFilters,
                onClearQuery = viewModel::clearQuery,
                onCanyonClick = onCanyonClick,
                modeToggle = modeToggle,
                contentPadding = contentPadding,
                asTab = !viewModel.isPick,
                withoutForbidden = viewModel.isStayPick,
                // For a stay, the map shows and centres on its place; else the position found by the list.
                knownLatitude = uiState.userLatitude,
                knownLongitude = uiState.userLongitude,
                focusKnownLocation = viewModel.isStayPick,
                // The distances of a stay start from its place, never from the user.
                onUserLocated = if (viewModel.isStayPick) ({ _, _ -> }) else viewModel::onUserLocationUpdated,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(if (viewModel.isPick) Modifier else Modifier.statusBarsPadding()),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(contentWidth)
                        .align(Alignment.TopCenter)
                        .padding(horizontal = screenHorizontalPadding),
                ) {
                    Spacer(modifier = Modifier.height(spacing.sm))

                    modeToggle()

                    Spacer(modifier = Modifier.height(spacing.sm))

                    if (viewModel.isOutingPick) {
                        Text(
                            stringResource(R.string.outing_pick_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = spacing.sm),
                        )
                    }

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

                    val shownEstimates = estimates
                    val listedResults = shownEstimates?.listed(uiState.results) ?: uiState.results
                    // The estimate filter goes under the count when the line is too short for both.
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            // Without query nor filter nothing is listed: the count is the catalog size, not results.
                            text = when {
                                uiState.isResultListDeferred ->
                                    pluralStringResource(
                                        R.plurals.search_catalog_count,
                                        uiState.totalResultsCount,
                                        uiState.totalResultsCount,
                                    )

                                shownEstimates?.onlyLikelyNormal == true -> pluralStringResource(
                                    R.plurals.search_estimate_results,
                                    listedResults.size,
                                    listedResults.size,
                                    shownEstimates.candidateIds.size,
                                )

                                else -> pluralStringResource(
                                    R.plurals.search_results_count,
                                    uiState.totalResultsCount,
                                    uiState.totalResultsCount,
                                )
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        shownEstimates?.let { current ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                current.dayOffset?.let { offset ->
                                    EstimateDayChip(dayOffset = offset, onSelect = viewModel::selectEstimateDay)
                                }
                                FilterChip(
                                    selected = current.onlyLikelyNormal,
                                    onClick = viewModel::toggleOnlyLikelyNormal,
                                    label = { Text(stringResource(R.string.search_estimate_filter), maxLines = 1) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Insights, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                )
                            }
                        }
                    }
                    shownEstimates?.takeIf { it.isComputing }?.let { current ->
                        Text(
                            text = stringResource(R.string.search_estimate_progress, current.computedCount, current.computedIds.size),
                            style = MaterialTheme.typography.bodySmall,
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
                            favorites = uiState.favorites,
                            nearby = uiState.nearby,
                            criteria = uiState.criteria,
                            descendedIds = uiState.descendedIds,
                            onCanyonClick = onCanyonClick,
                            onClearRecentCanyons = viewModel::clearRecentCanyons,
                            // For a stay, distances are from its place: there is no "around me".
                            onAroundMe = if (viewModel.isStayPick) {
                                null
                            } else {
                                { requestCurrentLocation(applyDistanceSortWhenReady = true) }
                            },
                            isLocating = uiState.isLocating,
                            bottomPadding = contentPadding.calculateBottomPadding() + 96.dp,
                            modifier = Modifier.weight(1f),
                        )
                    } else if (!uiState.isLoading && listedResults.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.no_results),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // A name found nowhere: the canyon may be missing from the app.
                            val query = uiState.queryDraft.trim()
                            if (onProposeCanyon != null && query.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.search_propose_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(
                                    onClick = { onProposeCanyon(query) },
                                    modifier = Modifier.testTag(TestTags.SEARCH_PROPOSE_CANYON),
                                ) {
                                    Icon(Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(stringResource(R.string.search_propose_action), modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 96.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(listedResults, key = { it.id }) { canyon ->
                                CanyonSummaryCard(
                                    canyon = canyon.toSummary(),
                                    onClick = { onCanyonClick(canyon.id) },
                                    isDescended = canyon.isDescended,
                                    info = stayPick?.let { pick -> driveInfo(pick, canyon) } ?: distanceInfo(uiState.criteria, canyon),
                                    plannedOn = stayPick?.plannedOn?.get(canyon.id),
                                    estimate = shownEstimates?.byCanyon?.get(canyon.id),
                                )
                            }
                            item { Spacer(modifier = Modifier.height(96.dp)) }
                        }
                    }
                }
            }
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

/** Before any query or filter: the canyons around the user once located, and those opened recently, available offline. */
@Composable
private fun SearchIdleContent(
    recentCanyons: List<CanyonSummary>,
    favorites: List<CanyonSearchItem>,
    nearby: List<CanyonSearchItem>,
    criteria: SearchCriteria,
    descendedIds: Set<Int>,
    onCanyonClick: (Int) -> Unit,
    onClearRecentCanyons: () -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    /** Lists the canyons by distance from the position, asked for if needed. */
    onAroundMe: (() -> Unit)? = null,
    isLocating: Boolean = false,
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
        // An outing to prepare: most often one of the user's favorites.
        if (favorites.isNotEmpty()) {
            item(key = "favorites-title") {
                DcSectionHeader(
                    title = stringResource(R.string.search_favorites_title),
                    modifier = Modifier.testTag(TestTags.SEARCH_FAVORITES),
                )
            }
            items(favorites, key = { "favorite-${it.id}" }) { canyon ->
                CanyonSummaryCard(
                    canyon = canyon.toSummary(),
                    onClick = { onCanyonClick(canyon.id) },
                    isDescended = canyon.isDescended,
                    info = distanceInfo(criteria, canyon),
                )
            }
        }
        // Without a name in mind, the nearest canyons are the usual start: shown at once when the
        // position is known, then all of them by distance in one tap, offline.
        if (nearby.isNotEmpty()) {
            item(key = "nearby-title") {
                DcSectionHeader(title = stringResource(R.string.search_nearby_title), modifier = Modifier.testTag(TestTags.SEARCH_NEARBY))
            }
            items(nearby, key = { "nearby-${it.id}" }) { canyon ->
                CanyonSummaryCard(
                    canyon = canyon.toSummary(),
                    onClick = { onCanyonClick(canyon.id) },
                    isDescended = canyon.isDescended,
                    info = distanceInfo(criteria, canyon),
                )
            }
        }
        onAroundMe?.let { onClick ->
            item(key = "around-me") {
                FilledTonalButton(
                    onClick = onClick,
                    enabled = !isLocating,
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.SEARCH_AROUND_ME),
                ) {
                    if (isLocating) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.NearMe, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        stringResource(if (nearby.isEmpty()) R.string.search_around_me else R.string.search_nearby_all),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
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

/** The switch between the list of the canyons found and their map, at the top of both. */
@Composable
private fun ExploreModeToggle(mode: SearchResultViewMode, onModeSelected: (SearchResultViewMode) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDcColors.current
    Surface(
        modifier = modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceRaised,
        contentColor = colors.textPrimary,
        border = BorderStroke(1.dp, colors.borderSubtle),
        shadowElevation = 2.dp,
    ) {
        Row(modifier = Modifier.padding(4.dp).selectableGroup()) {
            ExploreModeSegment(
                selected = mode == SearchResultViewMode.LIST,
                label = stringResource(R.string.explore_mode_list),
                icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = { onModeSelected(SearchResultViewMode.LIST) },
                modifier = Modifier.weight(1f).testTag(TestTags.EXPLORE_MODE_LIST),
            )
            ExploreModeSegment(
                selected = mode == SearchResultViewMode.MAP,
                label = stringResource(R.string.explore_mode_map),
                icon = { Icon(painterResource(R.drawable.map_search_24), contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = { onModeSelected(SearchResultViewMode.MAP) },
                modifier = Modifier.weight(1f).testTag(TestTags.EXPLORE_MODE_MAP),
            )
        }
    }
}

@Composable
private fun ExploreModeSegment(
    selected: Boolean,
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDcColors.current
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier.fillMaxHeight().semantics { role = Role.Tab },
        shape = RoundedCornerShape(20.dp),
        color = if (selected) colors.primaryAction else Color.Transparent,
        contentColor = if (selected) colors.primaryActionContent else colors.textSecondary,
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            icon()
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** "≈ 15 min" by road from the place of the stay, at the end of the place line of its card. */
@Composable
private fun driveInfo(pick: SearchStayPick, canyon: CanyonSearchItem): CanyonCardInfo? {
    val drive = pick.driveTo(canyon) ?: return null
    val minutes = formatDurationMinutes(drive.minutes)
    val estimated = drive.source == DriveTimeSource.ESTIMATE
    return CanyonCardInfo(
        icon = Icons.Default.DirectionsCar,
        text = if (estimated) "≈ $minutes" else minutes,
        description = stringResource(if (estimated) R.string.stay_drive_estimate else R.string.stay_drive, minutes),
    )
}

/** "12 km" in a straight line from the user, once their position is known. */
@Composable
private fun distanceInfo(criteria: SearchCriteria, canyon: CanyonSearchItem): CanyonCardInfo? {
    val latitude = criteria.userLatitude ?: return null
    val longitude = criteria.userLongitude ?: return null
    val canyonLatitude = canyon.representativeLat ?: return null
    val canyonLongitude = canyon.representativeLng ?: return null
    val km = haversineKm(latitude, longitude, canyonLatitude, canyonLongitude)
    val locale = LocalLocale.current.platformLocale
    // A tenth below ten kilometres, whole kilometres above.
    val text = if (km < 10) String.format(locale, "%.1f km", km) else "${km.roundToInt()} km"
    return CanyonCardInfo(icon = Icons.Default.NearMe, text = text, description = stringResource(R.string.search_distance_away, text))
}

/** The day of the estimates shown in the list: today, tomorrow or the day after. */
@Composable
private fun EstimateDayChip(dayOffset: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val labels = listOf(R.string.prediction_today, R.string.prediction_tomorrow, R.string.prediction_day_after_tomorrow)
    Box {
        FilterChip(
            selected = dayOffset > 0,
            onClick = { expanded = true },
            label = { Text(stringResource(labels[dayOffset.coerceIn(0, labels.lastIndex)]), maxLines = 1) },
            trailingIcon = { Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp)) },
            modifier = Modifier.testTag(TestTags.SEARCH_ESTIMATE_DAY),
        )
        DcDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            labels.forEachIndexed { index, label ->
                DcDropdownMenuItem(
                    text = stringResource(label),
                    isSelected = index == dayOffset,
                    onClick = {
                        expanded = false
                        onSelect(index)
                    },
                )
            }
        }
    }
}
