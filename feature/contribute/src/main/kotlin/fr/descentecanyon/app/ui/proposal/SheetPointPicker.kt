package fr.descentecanyon.app.ui.proposal

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.SheetPoint
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.location.hasLocationPermission
import fr.descentecanyon.app.ui.location.loadCurrentDeviceLocation
import fr.descentecanyon.app.ui.map.MapCameraState
import fr.descentecanyon.app.ui.map.MapFocusRequest
import fr.descentecanyon.app.ui.map.MapLibreView
import fr.descentecanyon.app.ui.test.TestTags
import java.util.Locale

/** The point types a sheet can hold, in the order they are offered. */
internal val SHEET_POINT_TYPES = listOf(
    GeoPointType.ENTREE,
    GeoPointType.SORTIE,
    GeoPointType.PARKING_AVAL,
    GeoPointType.PARKING_AMONT,
    GeoPointType.ECHAPPATOIRE,
    GeoPointType.POINT_REMARQUABLE,
)

@Composable
internal fun pointTypeLabel(type: GeoPointType): String = stringResource(
    when (type) {
        GeoPointType.PARKING_AMONT -> R.string.geo_point_parking_upstream
        GeoPointType.PARKING_AVAL -> R.string.geo_point_parking_downstream
        GeoPointType.ENTREE -> R.string.geo_point_entry
        GeoPointType.SORTIE -> R.string.geo_point_exit
        GeoPointType.POINT_REMARQUABLE -> R.string.geo_point_remarkable
        GeoPointType.ECHAPPATOIRE -> R.string.geo_point_escape
        GeoPointType.UNKNOWN -> R.string.geo_point_gps
    },
)

internal fun coordinatesText(latitude: Double, longitude: Double): String = String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude)

/** "45.12345, 5.6789" or "45.12345 5.6789", as copied from a map or a topo; null otherwise. */
internal fun parseCoordinates(text: String): Pair<Double, Double>? {
    val parts = text.trim().split(Regex("""[\s,;]+""")).filter { it.isNotBlank() }
    if (parts.size != 2) return null
    val latitude = parts[0].toDoubleOrNull() ?: return null
    val longitude = parts[1].toDoubleOrNull() ?: return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return latitude to longitude
}

/**
 * One point of the sheet, placed under the pin at the middle of the map (or typed as coordinates),
 * with the other points of the sheet and the catalog canyons around shown on the map.
 */
@Composable
internal fun SheetPointPicker(
    initial: SheetPoint,
    isNew: Boolean,
    otherMarkers: List<CanyonSummary>,
    onSave: (SheetPoint) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalDcColors.current
    var type by rememberSaveable { mutableStateOf(initial.type) }
    var title by rememberSaveable { mutableStateOf(initial.title) }
    val initialCamera = remember {
        MapCameraState(
            initial.latitude,
            initial.longitude,
            if (isNew &&
                otherMarkers.isEmpty()
            ) {
                START_ZOOM
            } else {
                POINT_ZOOM
            },
        )
    }
    var center by remember { mutableStateOf(initialCamera) }
    var coordinates by rememberSaveable { mutableStateOf("") }
    var position by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var locationRequest by remember { mutableIntStateOf(0) }
    var focus by remember { mutableStateOf<MapFocusRequest?>(null) }
    var locationUnavailable by remember { mutableStateOf(false) }

    fun locate() {
        loadCurrentDeviceLocation(
            context,
            onLocation = { latitude, longitude ->
                position = latitude to longitude
                locationRequest++
            },
            onUnavailable = { locationUnavailable = true },
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.any { it }) locate() else locationUnavailable = true
    }
    // A first point of a new sheet starts where the user is, when the app may know it.
    LaunchedEffect(Unit) {
        if (isNew && otherMarkers.isEmpty() && context.hasLocationPermission()) locate()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                CompactAppBar(
                    title = pointTypeLabel(type),
                    navigation = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel)) }
                    },
                    actions = {
                        TextButton(
                            onClick = { onSave(SheetPoint(type, round(center.latitude), round(center.longitude), title.trim())) },
                            modifier = Modifier.testTag(TestTags.PROPOSAL_POINT_SAVE),
                        ) { Text(stringResource(R.string.proposal_point_save)) }
                    },
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SHEET_POINT_TYPES.forEach { option ->
                        FilterChip(selected = option == type, onClick = { type = option }, label = { Text(pointTypeLabel(option)) })
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    MapLibreView(
                        markers = otherMarkers,
                        userLatitude = position?.first,
                        userLongitude = position?.second,
                        onMarkerClick = {},
                        onCameraChanged = { center = it },
                        clusterMarkers = false,
                        persistedCameraState = initialCamera,
                        focusLocationRequestId = locationRequest,
                        focusRequest = focus,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // The pin's tip marks the middle of the map.
                    Icon(
                        Icons.Default.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(PIN_SIZE).offset(y = -PIN_SIZE / 2),
                    )
                    SmallFloatingActionButton(
                        onClick = {
                            locationUnavailable = false
                            if (context.hasLocationPermission()) {
                                locate()
                            } else {
                                permissionLauncher.launch(
                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                                )
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.places_my_position))
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = coordinates,
                        onValueChange = { text ->
                            coordinates = text
                            parseCoordinates(text)?.let { (latitude, longitude) ->
                                focus = MapFocusRequest((focus?.id ?: 0) + 1, latitude, longitude, POINT_ZOOM)
                            }
                        },
                        label = { Text(stringResource(R.string.proposal_point_coordinates)) },
                        placeholder = { Text(coordinatesText(center.latitude, center.longitude)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(TITLE_MAX) },
                        label = { Text(stringResource(R.string.proposal_point_title)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (locationUnavailable) {
                            stringResource(R.string.places_position_unavailable)
                        } else {
                            stringResource(R.string.proposal_point_map_hint, coordinatesText(center.latitude, center.longitude))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (locationUnavailable) MaterialTheme.colorScheme.error else colors.textSecondary,
                    )
                }
            }
        }
    }
}

private fun round(value: Double): Double = Math.round(value * 1_000_000.0) / 1_000_000.0

private val PIN_SIZE = 40.dp
private const val POINT_ZOOM = 15.0
private const val START_ZOOM = 6.0
private const val TITLE_MAX = 80
