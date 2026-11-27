package fr.descentecanyon.app.ui.map

import android.Manifest
import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.SearchCriteria
import fr.descentecanyon.app.perf.PerformanceTrace
import fr.descentecanyon.app.ui.common.asString
import fr.descentecanyon.app.ui.components.CanyonSummaryCard
import fr.descentecanyon.app.ui.components.CanyonSummaryCardVariant
import fr.descentecanyon.app.ui.design.DcEmptyState
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.LocalDcSpacing
import fr.descentecanyon.app.ui.design.excludeFromBackGesture
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.design.rememberDcScreenHorizontalPadding
import fr.descentecanyon.app.ui.location.hasLocationPermission
import fr.descentecanyon.app.ui.location.loadCurrentDeviceLocation
import fr.descentecanyon.app.ui.location.requestLocationSettings
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.maplibre.android.geometry.LatLngBounds

private val MAP_RESULTS_SHEET_PEEK_HEIGHT = 72.dp
private const val MAP_RESULTS_SHEET_COMPACT_ITEM_LIMIT = 2
private val MAP_BOTTOM_NAVIGATION_CONTENT_HEIGHT = 80.dp

/** Height of the controls over the map: the list/map switch, then the filters and the position. */
private val MAP_CONTROLS_HEIGHT = 128.dp

/**
 * The map of Explorer: the canyons of the search [criteria] (shared with the list), the canyons
 * visible listed in a sheet, the canyon tapped shown in a card. [modeToggle] goes back to the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreMap(
    criteria: SearchCriteria,
    onOpenFilters: () -> Unit,
    onClearFilters: () -> Unit,
    onClearQuery: () -> Unit,
    onCanyonClick: (Int) -> Unit,
    modeToggle: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    /** The Explorer tab: under the status bar, over the bottom bar. Else under an app bar (a day of a stay). */
    asTab: Boolean = true,
    /** Leaves out the canyons that cannot be planned (a day of a stay). */
    withoutForbidden: Boolean = false,
    /** A position known from the list, shown on the map; with [focusKnownLocation], the map moves to it (the place of a stay). */
    knownLatitude: Double? = null,
    knownLongitude: Double? = null,
    focusKnownLocation: Boolean = false,
    /** The user's position found from the map, for the distances of the list. */
    onUserLocated: (Double, Double) -> Unit = { _, _ -> },
    viewModel: MapViewModel = hiltViewModel(),
) {
    LaunchedEffect(criteria, withoutForbidden) { viewModel.setCriteria(criteria, withoutForbidden) }
    LaunchedEffect(knownLatitude, knownLongitude, focusKnownLocation) {
        if (knownLatitude == null || knownLongitude == null) return@LaunchedEffect
        if (focusKnownLocation) {
            viewModel.focusAroundUser(knownLatitude, knownLongitude)
        } else {
            viewModel.onKnownUserLocation(knownLatitude, knownLongitude)
        }
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val dcColors = LocalDcColors.current
    val contentWidth = rememberDcContentWidth()
    val screenHorizontalPadding = rememberDcScreenHorizontalPadding()
    val layoutDirection = LocalLayoutDirection.current
    val configuration = LocalConfiguration.current
    val initialFocusLocationRequestId = remember { uiState.focusLocationRequestId }
    val bottomSheetState = rememberStandardBottomSheetState()
    val bottomSheetScaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = bottomSheetState,
    )
    val navigationBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomNavigationInset = if (asTab) {
        maxOf(contentPadding.calculateBottomPadding(), MAP_BOTTOM_NAVIGATION_CONTENT_HEIGHT + navigationBarBottom)
    } else {
        navigationBarBottom
    }
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topInset = if (asTab) statusBarPadding else 0.dp
    val compassTopMargin = topInset + MAP_CONTROLS_HEIGHT + if (criteria.query.isNotBlank()) 44.dp else 0.dp
    val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val navigationBarEndPadding = navigationBarPadding.calculateRightPadding(layoutDirection)
    val mapControlsEndPadding = screenHorizontalPadding + navigationBarEndPadding
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    // MapLibre must receive system insets before its native compass is created.
    val areMapInsetsReady = statusBarPadding > 0.dp && (!isLandscape || navigationBarEndPadding > 0.dp)
    var visibleBounds by remember { mutableStateOf<LatLngBounds?>(null) }
    // A name or a filter whose canyons are all out of sight: the map moves to show them (once per search).
    val density = LocalDensity.current
    var boundsRequest by remember { mutableStateOf<MapBoundsRequest?>(null) }
    val isNarrowed = criteria.query.isNotBlank() || criteria.activeFilterCount() > 0
    LaunchedEffect(uiState.mapCanyons, isNarrowed) {
        if (!isNarrowed || uiState.isLoading || uiState.mapCanyons.isEmpty()) return@LaunchedEffect
        val bounds = snapshotFlow { visibleBounds }.filterNotNull().first()
        if (uiState.mapCanyons.any { it.isWithin(bounds) }) return@LaunchedEffect
        val points = uiState.mapCanyons.mapNotNull { canyon -> canyon.latitude?.let { lat -> canyon.longitude?.let { lat to it } } }
        if (points.isEmpty()) return@LaunchedEffect
        with(density) {
            boundsRequest = MapBoundsRequest(
                id = (boundsRequest?.id ?: 0) + 1,
                south = points.minOf { it.first },
                west = points.minOf { it.second },
                north = points.maxOf { it.first },
                east = points.maxOf { it.second },
                paddingTop = (topInset + MAP_CONTROLS_HEIGHT + 16.dp).roundToPx(),
                paddingBottom = (MAP_RESULTS_SHEET_PEEK_HEIGHT + bottomNavigationInset + 16.dp).roundToPx(),
                paddingSides = 32.dp.roundToPx(),
            )
        }
    }
    val visibleCanyons = remember(uiState.mapCanyons, visibleBounds) {
        visibleBounds?.let { bounds ->
            uiState.mapCanyons.filter { canyon -> canyon.isWithin(bounds) }
        } ?: uiState.mapCanyons
    }
    val locationSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            focusAroundUserFromDevice(context, viewModel, onUserLocated)
        } else {
            viewModel.onLocationUnavailable()
        }
    }

    fun focusAroundUserWithSettingsCheck() {
        requestLocationSettings(
            context = context,
            onEnabled = { focusAroundUserFromDevice(context, viewModel, onUserLocated) },
            onResolutionRequired = { request -> locationSettingsLauncher.launch(request) },
            onUnavailable = viewModel::onLocationUnavailable,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.onLocationPermissionResult(granted)
        if (granted) {
            focusAroundUserWithSettingsCheck()
        }
    }

    LaunchedEffect(Unit) {
        PerformanceTrace.logEvent("map_screen_visible")
    }

    LaunchedEffect(Unit) {
        if (context.hasLocationPermission()) {
            viewModel.onLocationPermissionResult(true)
        }
        // A map never moved centres on the user when the position is already allowed and on: no
        // permission or "turn on location" prompt nobody asked for. A stay centres on its place.
        if (uiState.isFirstOpen && !focusKnownLocation) {
            viewModel.onFirstOpenHandled()
            if (context.hasLocationPermission()) {
                viewModel.onLocationLookupStarted()
                loadCurrentDeviceLocation(
                    context = context,
                    onLocation = { latitude, longitude ->
                        viewModel.focusAroundUser(latitude, longitude)
                        onUserLocated(latitude, longitude)
                    },
                    onUnavailable = viewModel::onFirstPositionUnavailable,
                )
            }
        }
    }

    LaunchedEffect(uiState.transientMessage) {
        uiState.transientMessage?.let { message ->
            snackbarHostState.showSnackbar(message.asString(context))
            viewModel.clearTransientMessage()
        }
    }

    LaunchedEffect(bottomSheetState.currentValue) {
        if (bottomSheetState.currentValue == SheetValue.Expanded) {
            viewModel.clearSelectedCanyon()
        }
    }

    fun focusAroundUser() {
        if (context.hasLocationPermission()) {
            viewModel.onLocationPermissionResult(true)
            focusAroundUserWithSettingsCheck()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    BottomSheetScaffold(
        sheetContent = {
            MapResultsSheet(
                isLoading = uiState.isLoading,
                visibleCanyons = visibleCanyons,
                isExpanded = bottomSheetState.currentValue == SheetValue.Expanded,
                hasActiveFilters = uiState.criteria.activeFilterCount() > 0,
                bottomContentPadding = bottomNavigationInset,
                onClearFilters = onClearFilters,
                onCanyonClick = onCanyonClick,
            )
        },
        scaffoldState = bottomSheetScaffoldState,
        modifier = modifier
            .fillMaxSize(),
        sheetPeekHeight = MAP_RESULTS_SHEET_PEEK_HEIGHT + bottomNavigationInset,
        sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        sheetContainerColor = dcColors.surfaceOverlay,
        sheetContentColor = dcColors.textPrimary,
        sheetDragHandle = null,
        containerColor = dcColors.backgroundBase,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (areMapInsetsReady) {
                MapLibreView(
                    markers = uiState.mapCanyons,
                    userLatitude = uiState.userLatitude,
                    userLongitude = uiState.userLongitude,
                    onMarkerClick = viewModel::selectCanyon,
                    onMapInteraction = viewModel::clearSelectedCanyon,
                    onVisibleBoundsChanged = { bounds -> visibleBounds = bounds },
                    onCameraChanged = viewModel::onCameraChanged,
                    compassTopMargin = compassTopMargin,
                    compassEndMargin = mapControlsEndPadding,
                    compassFadeFacingNorth = false,
                    // The results sheet covers the bottom of the map.
                    attributionTopMargin = compassTopMargin,
                    persistedCameraState = uiState.cameraState,
                    boundsRequest = boundsRequest,
                    focusLocationRequestId = uiState.focusLocationRequestId.takeUnless {
                        it == initialFocusLocationRequestId
                    } ?: 0,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topInset)
                    .width(contentWidth)
                    .padding(
                        start = screenHorizontalPadding,
                        top = 12.dp,
                        end = mapControlsEndPadding,
                        bottom = 12.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                modeToggle()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MapFilterControl(
                        activeFilterCount = criteria.activeFilterCount(),
                        totalResultsCount = uiState.totalResultsCount,
                        onClick = onOpenFilters,
                        onClear = onClearFilters,
                    )
                    MapLocationControl(
                        isLocating = uiState.isLocating,
                        onClick = ::focusAroundUser,
                    )
                }
                // The name searched in the list narrows the map too: shown, and removable here.
                if (criteria.query.isNotBlank()) {
                    MapQueryChip(query = criteria.query, onClear = onClearQuery)
                }
            }

            uiState.selectedCanyon?.let { canyon ->
                CanyonSummaryCard(
                    canyon = canyon,
                    onClick = { onCanyonClick(canyon.id) },
                    variant = CanyonSummaryCardVariant.MapSheet,
                    isFavorite = uiState.isSelectedCanyonFavorite,
                    onFavoriteClick = viewModel::toggleSelectedCanyonFavorite,
                    isDescended = canyon.id in uiState.descendedIds,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .width(contentWidth)
                        .padding(
                            start = screenHorizontalPadding,
                            end = screenHorizontalPadding,
                            bottom = MAP_RESULTS_SHEET_PEEK_HEIGHT + bottomNavigationInset + 12.dp,
                        )
                        .alpha(0.94f),
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topInset)
                    .width(contentWidth)
                    .padding(
                        start = screenHorizontalPadding,
                        top = MAP_CONTROLS_HEIGHT,
                        end = screenHorizontalPadding,
                    ),
            )
        }
    }
}

/** The name searched, over the map, with a button to search no name. */
@Composable
private fun MapQueryChip(query: String, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDcColors.current
    Surface(
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(18.dp),
        color = colors.surfaceRaised,
        contentColor = colors.textPrimary,
        shadowElevation = 4.dp,
    ) {
        Row(modifier = Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
            Text(
                text = query,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp).widthIn(max = 200.dp),
            )
            IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.search_clear_query), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun MapFilterControl(
    activeFilterCount: Int,
    totalResultsCount: Int,
    onClick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDcColors.current
    val hasActiveFilters = activeFilterCount > 0
    // Opaque over the map; active, it reads like the filters button of the list.
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(48.dp)
            .widthIn(max = 280.dp),
        shape = RoundedCornerShape(24.dp),
        color = if (hasActiveFilters) colors.water.copy(alpha = 0.16f).compositeOver(colors.surfaceRaised) else colors.surfaceRaised,
        contentColor = colors.textPrimary,
        border = if (hasActiveFilters) BorderStroke(1.dp, colors.primaryAction.copy(alpha = 0.5f)) else null,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = if (hasActiveFilters) 2.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = null,
                tint = if (hasActiveFilters) colors.primaryAction else colors.textMuted,
            )
            Text(
                text = if (hasActiveFilters) {
                    stringResource(R.string.search_filters) + " · " +
                        pluralStringResource(R.plurals.search_filters_active_count, activeFilterCount, activeFilterCount)
                } else {
                    stringResource(R.string.search_filters)
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                color = colors.textPrimary,
            )
            Text(
                text = pluralStringResource(R.plurals.search_results_count, totalResultsCount, totalResultsCount),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                color = colors.textSecondary,
            )
            if (hasActiveFilters) {
                IconButton(onClick = onClear, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = stringResource(R.string.search_clear_filters),
                    )
                }
            }
        }
    }
}

@Composable
private fun MapLocationControl(isLocating: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDcColors.current
    Surface(
        modifier = modifier.size(48.dp).excludeFromBackGesture(),
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceRaised,
        contentColor = colors.textPrimary,
        shadowElevation = 4.dp,
    ) {
        IconButton(
            onClick = onClick,
            enabled = !isLocating,
        ) {
            if (isLocating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = colors.primaryAction,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = stringResource(R.string.map_my_location),
                    tint = colors.primaryAction,
                )
            }
        }
    }
}

@Composable
private fun MapResultsSheet(
    isLoading: Boolean,
    visibleCanyons: List<CanyonSummary>,
    isExpanded: Boolean,
    hasActiveFilters: Boolean,
    bottomContentPadding: Dp,
    onClearFilters: () -> Unit,
    onCanyonClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDcColors.current
    val spacing = LocalDcSpacing.current
    val hasCompactContent = !isLoading &&
        visibleCanyons.size in 1..MAP_RESULTS_SHEET_COMPACT_ITEM_LIMIT
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (hasCompactContent) Modifier else Modifier.fillMaxHeight(0.82f),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp),
                shape = RoundedCornerShape(2.dp),
                color = colors.borderSubtle,
            ) {}
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.map_visible_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Icon(
                imageVector = Icons.Default.Explore,
                contentDescription = null,
                tint = colors.primaryAction,
            )
        }

        val contentAlpha = if (isExpanded) 1f else 0f
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .alpha(contentAlpha),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            visibleCanyons.isEmpty() -> {
                EmptyVisibleCard(
                    hasActiveFilters = hasActiveFilters,
                    onClearFilters = onClearFilters,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .alpha(contentAlpha)
                        .padding(horizontal = spacing.lg, vertical = spacing.md),
                )
            }

            hasCompactContent -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(contentAlpha)
                        .padding(
                            start = spacing.lg,
                            top = spacing.sm,
                            end = spacing.lg,
                            bottom = 24.dp + bottomContentPadding,
                        ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    visibleCanyons.forEach { canyon ->
                        NearbyCanyonCard(
                            canyon = canyon,
                            onClick = { onCanyonClick(canyon.id) },
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .alpha(contentAlpha),
                    contentPadding = PaddingValues(
                        start = spacing.lg,
                        top = spacing.sm,
                        end = spacing.lg,
                        bottom = 24.dp + bottomContentPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = visibleCanyons,
                        key = { canyon -> canyon.id },
                    ) { canyon ->
                        NearbyCanyonCard(
                            canyon = canyon,
                            onClick = { onCanyonClick(canyon.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyVisibleCard(hasActiveFilters: Boolean, onClearFilters: () -> Unit, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        DcEmptyState(
            title = stringResource(R.string.map_visible_empty_title),
            body = stringResource(R.string.map_visible_empty_description),
            icon = Icons.Default.Explore,
            modifier = modifier,
        )
        if (hasActiveFilters) {
            TextButton(onClick = onClearFilters) {
                Text(stringResource(R.string.search_clear_filters))
            }
        }
    }
}

private fun focusAroundUserFromDevice(context: Context, viewModel: MapViewModel, onUserLocated: (Double, Double) -> Unit) {
    viewModel.onLocationLookupStarted()
    loadCurrentDeviceLocation(
        context = context,
        onLocation = { latitude, longitude ->
            viewModel.focusAroundUser(latitude, longitude)
            onUserLocated(latitude, longitude)
        },
        onUnavailable = viewModel::onLocationUnavailable,
    )
}

private fun CanyonSummary.isWithin(bounds: LatLngBounds): Boolean {
    val lat = latitude ?: return false
    val lon = longitude ?: return false
    val latInRange = lat in bounds.latitudeSouth..bounds.latitudeNorth
    val lonInRange = if (bounds.longitudeWest <= bounds.longitudeEast) {
        lon in bounds.longitudeWest..bounds.longitudeEast
    } else {
        lon >= bounds.longitudeWest || lon <= bounds.longitudeEast
    }
    return latInRange && lonInRange
}
