package fr.descentecanyon.app.ui.canyon

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CommunityTrack
import fr.descentecanyon.app.domain.model.CommunityTracks
import fr.descentecanyon.app.domain.model.GeoBounds
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.TrackStanding
import fr.descentecanyon.app.ui.common.asString
import fr.descentecanyon.app.ui.design.excludeFromBackGesture
import fr.descentecanyon.app.ui.location.hasLocationPermission
import fr.descentecanyon.app.ui.location.observeDeviceLocation
import fr.descentecanyon.app.ui.location.requestLocationSettings
import fr.descentecanyon.app.ui.map.MapLibreView
import fr.descentecanyon.app.ui.map.UserTrackingMode
import fr.descentecanyon.app.ui.map.toMapTrackLine
import fr.descentecanyon.app.ui.map.trackColor
import fr.descentecanyon.app.ui.test.TestTags
import java.util.Locale
import kotlin.math.abs
import org.maplibre.android.geometry.LatLngBounds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanyonPointsMapScreen(
    canyonId: Int,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenEmergency: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: CanyonDetailViewModel = hiltViewModel(),
    tracksViewModel: CommunityTracksViewModel = hiltViewModel(),
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val tracksState = tracksViewModel.uiState.collectAsStateWithLifecycle().value

    LaunchedEffect(canyonId) {
        viewModel.ensureWatershedGeometryLoaded()
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        when {
            uiState.isLoading && uiState.canyonDetail == null -> Box(
                modifier = Modifier.fillMaxSize().padding(contentPadding).padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            uiState.canyonDetail != null -> CanyonPointsMapContent(
                detail = uiState.canyonDetail,
                watershedGeometryJson = uiState.watershedGeometryJson,
                tracks = tracksState.tracks,
                onBackClick = onBackClick,
                onOpenEmergency = onOpenEmergency,
                modifier = Modifier.padding(contentPadding),
            )

            else -> Box(
                modifier = Modifier.fillMaxSize().padding(contentPadding).padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { Text(uiState.error?.asString() ?: stringResource(R.string.error_generic)) }
        }
    }
}

@Composable
private fun CanyonPointsMapContent(
    detail: CanyonDetail,
    watershedGeometryJson: String?,
    tracks: CommunityTracks?,
    onBackClick: () -> Unit,
    onOpenEmergency: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    val hasWatershedPolygon = !watershedGeometryJson.isNullOrBlank()
    var showWatershed by rememberSaveable(detail.canyon.id) { mutableStateOf(false) }
    // Shared tracks: all drawn by default, each can be hidden from the legend; set-aside ones on demand.
    var tracksPanelOpen by rememberSaveable(detail.canyon.id) { mutableStateOf(false) }
    var hiddenTrackIds by rememberSaveable(detail.canyon.id) { mutableStateOf(listOf<String>()) }
    var showDiscreditedTracks by rememberSaveable(detail.canyon.id) { mutableStateOf(false) }
    val orderedTracks = tracks?.ordered.orEmpty()
    val trackLines = remember(orderedTracks, hiddenTrackIds, showDiscreditedTracks) {
        orderedTracks.mapIndexedNotNull { index, track ->
            val shown = track.id !in hiddenTrackIds && (track.standing != TrackStanding.DISCREDITED || showDiscreditedTracks)
            track.toMapTrackLine(index).takeIf { shown }
        }
    }
    var pointsExpanded by rememberSaveable(detail.canyon.id) { mutableStateOf(false) }
    var locationPermissionGranted by remember(detail.canyon.id) { mutableStateOf(context.hasLocationPermission()) }
    var trackingModeName by rememberSaveable(detail.canyon.id) { mutableStateOf(UserTrackingMode.NONE.name) }
    var pendingTrackingModeName by remember { mutableStateOf(UserTrackingMode.LOCATION.name) }
    var userLocation by remember { mutableStateOf<DeviceLocation?>(null) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var selectedPoint by remember(detail.canyon.id) { mutableStateOf<GeoPoint?>(null) }
    var resetNorthRequestId by remember { mutableStateOf(0) }
    var cameraBearing by remember { mutableStateOf(0.0) }
    val trackingMode = UserTrackingMode.entries.firstOrNull { it.name == trackingModeName }
        ?: UserTrackingMode.NONE
    val heading = rememberDeviceHeading(enabled = trackingMode != UserTrackingMode.NONE)

    fun applyLocationRequest() {
        trackingModeName = pendingTrackingModeName
    }

    val locationSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            applyLocationRequest()
        } else {
            locationMessage = resources.getString(R.string.map_location_settings_unavailable)
        }
    }

    fun verifyLocationSettings() {
        requestLocationSettings(
            context = context,
            onEnabled = ::applyLocationRequest,
            onResolutionRequired = { request -> locationSettingsLauncher.launch(request) },
            onUnavailable = {
                locationMessage = resources.getString(R.string.map_location_settings_unavailable)
            },
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        locationPermissionGranted = granted
        if (granted) {
            verifyLocationSettings()
        } else {
            locationMessage = resources.getString(R.string.map_location_permission_denied)
        }
    }

    fun activateLocationMode(mode: UserTrackingMode) {
        pendingTrackingModeName = mode.name
        if (context.hasLocationPermission()) {
            locationPermissionGranted = true
            verifyLocationSettings()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    fun cycleLocationTracking() {
        when (trackingMode) {
            UserTrackingMode.NONE -> activateLocationMode(UserTrackingMode.LOCATION)

            UserTrackingMode.LOCATION -> {
                if (!heading.isAvailable) {
                    locationMessage = resources.getString(R.string.map_heading_unavailable)
                } else {
                    activateLocationMode(UserTrackingMode.HEADING)
                }
            }

            UserTrackingMode.HEADING -> {
                trackingModeName = UserTrackingMode.LOCATION.name
                resetNorthRequestId += 1
            }
        }
    }

    DisposableEffect(locationPermissionGranted) {
        if (!locationPermissionGranted) {
            onDispose {}
        } else {
            val stopObserving = observeDeviceLocation(
                context = context,
                onLocation = { latitude, longitude -> userLocation = DeviceLocation(latitude, longitude) },
            )
            onDispose(stopObserving)
        }
    }

    LaunchedEffect(locationMessage) {
        locationMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            locationMessage = null
        }
    }

    val markers = remember(detail.geoPoints, context) {
        detail.geoPoints.mapIndexed { index, point ->
            fr.descentecanyon.app.domain.model.CanyonSummary(
                id = index + 1,
                nom = point.navigationLabel(context),
                pays = detail.canyon.pays,
                cotation = detail.canyon.cotation,
                url = detail.canyon.url,
                latitude = point.latitude,
                longitude = point.longitude,
                markerType = point.type,
            )
        }
    }
    val pointsByMarkerId = remember(detail.geoPoints) {
        detail.geoPoints.mapIndexed { index, point -> index + 1 to point }.toMap()
    }

    fun navigateTo(point: GeoPoint) {
        val label = Uri.encode(point.navigationLabel(context))
        val uri = "geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}($label)".toUri()
        try {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW, uri),
                    resources.getString(R.string.navigate_chooser_title),
                ),
            )
        } catch (_: ActivityNotFoundException) {
            locationMessage = resources.getString(R.string.map_navigation_unavailable)
        }
    }

    fun copyCoordinates(point: GeoPoint) {
        val coordinates = point.coordinatesText()
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(point.navigationLabel(context), coordinates))
        // Android 13+ confirms clipboard writes itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            locationMessage = resources.getString(R.string.coordinates_copied, coordinates)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val expandedPointsMaxHeight = minOf(300.dp, maxHeight * 0.5f)

        MapLibreView(
            markers = markers,
            userLatitude = userLocation?.latitude,
            userLongitude = userLocation?.longitude,
            userBearingDegrees = heading.degrees,
            userTrackingMode = trackingMode,
            positionCompassWithControls = true,
            // The points panel covers the bottom of the map.
            attributionTopMargin = 68.dp,
            resetNorthRequestId = resetNorthRequestId,
            onMarkerClick = { markerId ->
                pointsByMarkerId[markerId]?.let { point ->
                    selectedPoint = point
                    pointsExpanded = false
                }
            },
            clusterMarkers = false,
            watershedGeometryJson = watershedGeometryJson,
            watershedBounds = detail.watershed?.bounds?.toLatLngBounds(),
            showWatershed = showWatershed,
            trackLines = trackLines,
            onUserCameraMove = {
                if (trackingModeName != UserTrackingMode.NONE.name) {
                    trackingModeName = UserTrackingMode.NONE.name
                }
            },
            onCameraBearingChanged = { bearing ->
                cameraBearing = bearing
                if (
                    trackingMode == UserTrackingMode.HEADING &&
                    angularDifference(bearing, 0.0) < 2.0 &&
                    heading.degrees?.let { angularDifference(it, 0.0) > 5.0 } == true
                ) {
                    trackingModeName = UserTrackingMode.LOCATION.name
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MapControlButton(
                onClick = onBackClick,
                contentDescription = stringResource(R.string.back),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                Surface(
                    modifier = Modifier.height(48.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = 3.dp,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Box(contentAlignment = Alignment.CenterStart) {
                        Text(
                            text = detail.canyon.nom,
                            modifier = Modifier.padding(horizontal = 14.dp),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (hasWatershedPolygon) {
                WatershedToggle(
                    isVisible = showWatershed,
                    onClick = { showWatershed = !showWatershed },
                )
            }
            MapControlButton(
                onClick = onOpenEmergency,
                contentDescription = stringResource(R.string.emergency_open),
                modifier = Modifier.testTag(TestTags.EMERGENCY_BUTTON),
            ) {
                Icon(Icons.Default.Sos, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (orderedTracks.isNotEmpty()) {
                MapControlButton(
                    onClick = { tracksPanelOpen = !tracksPanelOpen },
                    contentDescription = stringResource(R.string.tracks_title),
                    selected = tracksPanelOpen,
                ) {
                    Icon(Icons.Default.Route, contentDescription = null)
                }
            }
            MapControlButton(
                onClick = ::cycleLocationTracking,
                contentDescription = stringResource(R.string.map_my_location),
                selected = trackingMode != UserTrackingMode.NONE,
            ) {
                Icon(
                    imageVector = if (trackingMode == UserTrackingMode.NONE) Icons.Default.MyLocation else Icons.Default.Navigation,
                    contentDescription = null,
                    modifier = if (trackingMode == UserTrackingMode.NONE) Modifier else Modifier.rotate((heading.degrees ?: 0.0).toFloat()),
                )
            }
        }

        if (tracksPanelOpen && orderedTracks.isNotEmpty()) {
            TracksLegend(
                tracks = orderedTracks,
                hiddenTrackIds = hiddenTrackIds,
                showDiscredited = showDiscreditedTracks,
                onToggleTrack = { id -> hiddenTrackIds = if (id in hiddenTrackIds) hiddenTrackIds - id else hiddenTrackIds + id },
                onShowDiscreditedChange = { showDiscreditedTracks = it },
                onClose = { tracksPanelOpen = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 72.dp, end = 72.dp, start = 12.dp),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp, start = 16.dp, end = 16.dp),
        )

        PointsPanel(
            detail = detail,
            expanded = pointsExpanded,
            selectedPoint = selectedPoint,
            onExpandedChange = { pointsExpanded = it },
            onNavigate = ::navigateTo,
            onCopyCoordinates = ::copyCoordinates,
            expandedListMaxHeight = expandedPointsMaxHeight,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Legend of the shared tracks: the colour of each one, and a switch to draw it or not. */
@Composable
private fun TracksLegend(
    tracks: List<CommunityTrack>,
    hiddenTrackIds: List<String>,
    showDiscredited: Boolean,
    onToggleTrack: (String) -> Unit,
    onShowDiscreditedChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.widthIn(max = 320.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(modifier = Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tracks_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.map_tracks_close))
                }
            }
            Column(modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                tracks.forEachIndexed { index, track ->
                    val discredited = track.standing == TrackStanding.DISCREDITED
                    if (discredited && !showDiscredited) return@forEachIndexed
                    val shown = track.id !in hiddenTrackIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = shown, role = Role.Checkbox, onValueChange = { onToggleTrack(track.id) })
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier
                                .size(width = 22.dp, height = 5.dp)
                                .background(Color(trackColor(index)), RoundedCornerShape(3.dp)),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    trackRoleLabel(track.role),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    trackStandingLabel(track),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = trackStandingColor(track.standing),
                                )
                            }
                            Text(
                                trackFacts(track),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Checkbox(checked = shown, onCheckedChange = null)
                    }
                }
            }
            if (tracks.any { it.standing == TrackStanding.DISCREDITED }) {
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = showDiscredited, role = Role.Switch, onValueChange = onShowDiscreditedChange)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.map_tracks_show_discredited),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = showDiscredited, onCheckedChange = null)
                }
            }
        }
    }
}

@Composable
private fun MapControlButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    contentDescription: String,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.excludeFromBackGesture(),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        shape = CircleShape,
        shadowElevation = 3.dp,
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(48.dp)
                .semantics { this.contentDescription = contentDescription },
        ) {
            content()
        }
    }
}

@Composable
private fun WatershedToggle(isVisible: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .height(48.dp)
            .clickable(onClick = onClick),
        color = if (isVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = if (isVisible) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.WaterDrop, contentDescription = null)
            Text(
                text = stringResource(if (isVisible) R.string.map_hide_watershed else R.string.map_watershed),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun PointsPanel(
    detail: CanyonDetail,
    expanded: Boolean,
    selectedPoint: GeoPoint?,
    onExpandedChange: (Boolean) -> Unit,
    onNavigate: (GeoPoint) -> Unit,
    onCopyCoordinates: (GeoPoint) -> Unit,
    expandedListMaxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 12.dp,
                end = 12.dp,
                bottom = 12.dp,
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.map_points_panel_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.map_points_panel_count, detail.geoPoints.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = null,
            )
        }
        selectedPoint?.takeIf { !expanded }?.let { point ->
            GeoPointRow(point = point, onNavigate = onNavigate, onCopyCoordinates = onCopyCoordinates)
            Spacer(Modifier.height(8.dp))
        }
        if (expanded) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = expandedListMaxHeight),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    detail.geoPoints.sortedBy { it.type.navigationPriority() },
                    key = { point -> point.id.takeIf { it != 0L } ?: "${point.latitude}:${point.longitude}" },
                ) { point ->
                    GeoPointRow(point = point, onNavigate = onNavigate, onCopyCoordinates = onCopyCoordinates)
                }
            }
        }
    }
}

@Composable
private fun GeoPointRow(point: GeoPoint, onNavigate: (GeoPoint) -> Unit, onCopyCoordinates: (GeoPoint) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = point.displayName(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = point.type.mapColor(),
            )
            point.sourceNote()?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            point.displaySubtitle()?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.map_location_coordinates, point.latitude, point.longitude),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onCopyCoordinates(point) }) {
            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy_coordinates))
        }
        TextButton(onClick = { onNavigate(point) }) {
            Icon(Icons.Default.Navigation, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.navigate))
        }
    }
}

/** Decimal degrees with a dot, the format GPS devices and map apps accept. */
internal fun GeoPoint.coordinatesText(): String = String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude)

private data class DeviceLocation(val latitude: Double, val longitude: Double)

private data class DeviceHeading(val degrees: Double?, val isAvailable: Boolean)

@Composable
private fun rememberDeviceHeading(enabled: Boolean): DeviceHeading {
    val context = LocalContext.current
    val sensorManager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val rotationVectorSensor = remember(sensorManager) {
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }
    var headingDegrees by remember { mutableStateOf<Double?>(null) }

    DisposableEffect(enabled, rotationVectorSensor, sensorManager) {
        if (!enabled || rotationVectorSensor == null) {
            onDispose {}
        } else {
            val rotationMatrix = FloatArray(9)
            val adjustedRotationMatrix = FloatArray(9)
            val orientation = FloatArray(3)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    val displayRotation = context.displayRotation()
                    val axes = displayAxes(displayRotation)
                    SensorManager.remapCoordinateSystem(
                        rotationMatrix,
                        axes.first,
                        axes.second,
                        adjustedRotationMatrix,
                    )
                    SensorManager.getOrientation(adjustedRotationMatrix, orientation)
                    val nextHeading = normalizeHeading(Math.toDegrees(orientation[0].toDouble()))
                    val currentHeading = headingDegrees
                    val smoothedHeading = currentHeading?.let { smoothHeading(it, nextHeading) } ?: nextHeading
                    if (currentHeading == null || angularDifference(currentHeading, smoothedHeading) >= 2.0) {
                        headingDegrees = smoothedHeading
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
            onDispose { sensorManager.unregisterListener(listener) }
        }
    }

    return DeviceHeading(degrees = headingDegrees, isAvailable = rotationVectorSensor != null)
}

private fun displayAxes(rotation: Int): Pair<Int, Int> = when (rotation) {
    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
}

private fun Context.displayRotation(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    display.rotation
} else {
    @Suppress("DEPRECATION")
    (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
}

internal fun normalizeHeading(degrees: Double): Double = (degrees % 360.0 + 360.0) % 360.0

internal fun smoothHeading(current: Double, target: Double): Double {
    val difference = ((target - current + 540.0) % 360.0) - 180.0
    return normalizeHeading(current + difference * 0.22)
}

internal fun angularDifference(first: Double, second: Double): Double = abs(((second - first + 540.0) % 360.0) - 180.0)

private fun GeoBounds.toLatLngBounds(): LatLngBounds = LatLngBounds.Builder()
    .include(org.maplibre.android.geometry.LatLng(minLatitude, minLongitude))
    .include(org.maplibre.android.geometry.LatLng(maxLatitude, maxLongitude))
    .build()
