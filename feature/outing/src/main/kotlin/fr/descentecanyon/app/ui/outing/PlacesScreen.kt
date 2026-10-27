package fr.descentecanyon.app.ui.outing

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.repository.PlaceRepository
import fr.descentecanyon.app.domain.repository.PreferencesRepository
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcDatePickerDialog
import fr.descentecanyon.app.ui.design.DcEmptyState
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.location.hasLocationPermission
import fr.descentecanyon.app.ui.location.loadCurrentDeviceLocation
import fr.descentecanyon.app.ui.map.MapCameraState
import fr.descentecanyon.app.ui.map.MapLibreView
import fr.descentecanyon.app.ui.test.TestTags
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The places outings start from (home, campsites), and where the place editor opens its map. */
@HiltViewModel
class PlacesViewModel @Inject constructor(private val placeRepository: PlaceRepository, preferencesRepository: PreferencesRepository) :
    ViewModel() {

    /** Null until loaded. */
    val places: StateFlow<List<Place>?> = placeRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** The last area seen on the map, for a new place. */
    val initialCamera: MapCameraState = newPlaceCamera(preferencesRepository)

    fun save(place: Place) {
        viewModelScope.launch { placeRepository.save(place) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { placeRepository.delete(id) }
    }

    companion object {
        const val PLACE_ZOOM = 13.0
        private const val COUNTRY_ZOOM = 5.0
        private const val FRANCE_LATITUDE = 46.5
        private const val FRANCE_LONGITUDE = 2.5
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Where the editor of a new place opens: the last area seen on the map, else France. */
        internal fun newPlaceCamera(preferencesRepository: PreferencesRepository): MapCameraState =
            preferencesRepository.getLastMapPosition()
                ?.let { MapCameraState(it.latitude, it.longitude, it.zoom.coerceAtLeast(PLACE_ZOOM - 2)) }
                ?: MapCameraState(FRANCE_LATITUDE, FRANCE_LONGITUDE, COUNTRY_ZOOM)
    }
}

@Composable
fun PlacesScreen(onBackClick: () -> Unit, modifier: Modifier = Modifier, viewModel: PlacesViewModel = hiltViewModel()) {
    val places by viewModel.places.collectAsStateWithLifecycle()
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)
    var editing by remember { mutableStateOf<Place?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Place?>(null) }
    if (adding || editing != null) {
        PlaceEditor(
            initial = editing,
            initialCamera =
                editing?.let { MapCameraState(it.latitude, it.longitude, PlacesViewModel.PLACE_ZOOM) } ?: viewModel.initialCamera,
            onSave = { place ->
                viewModel.save(place)
                adding = false
                editing = null
            },
            onDismiss = {
                adding = false
                editing = null
            },
        )
    }
    deleting?.let { place ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.places_delete_confirm, place.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(place.id)
                    deleting = null
                }) { Text(stringResource(R.string.places_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            CompactAppBar(
                title = stringResource(R.string.places_title),
                navigation = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.fillMaxHeight().width(contentWidth).testTag(TestTags.PLACES_LIST),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.places_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalDcColors.current.textSecondary,
                    )
                }
                if (places?.isEmpty() == true) {
                    item { DcEmptyState(title = stringResource(R.string.places_empty), icon = Icons.Default.Place) }
                }
                items(places.orEmpty(), key = { it.id }) { place ->
                    PlaceRow(
                        place = place,
                        onEdit = { editing = place },
                        onDelete = { deleting = place },
                    )
                }
                item {
                    Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().testTag(TestTags.PLACES_ADD)) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text(stringResource(R.string.places_add), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(place: Place, onEdit: () -> Unit, onDelete: () -> Unit) {
    DcCard(modifier = Modifier.fillMaxWidth(), onClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(place.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    coordinatesText(place.latitude, place.longitude),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalDcColors.current.textSecondary,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.places_edit)) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.places_delete)) }
        }
    }
}

/** A place: its name and its position under the pin at the middle of the map (moved by hand or to the phone's position). */
@Composable
internal fun PlaceEditor(initial: Place?, initialCamera: MapCameraState, onSave: (Place) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var center by remember { mutableStateOf(initialCamera) }
    var position by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var focusRequest by remember { mutableIntStateOf(0) }
    var locationUnavailable by remember { mutableStateOf(false) }
    fun locate() {
        loadCurrentDeviceLocation(
            context,
            onLocation = { latitude, longitude ->
                position = latitude to longitude
                focusRequest++
            },
            onUnavailable = { locationUnavailable = true },
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.any { it }) locate() else locationUnavailable = true
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                CompactAppBar(
                    title = stringResource(if (initial == null) R.string.places_add else R.string.places_edit),
                    navigation = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel)) }
                    },
                    actions = {
                        TextButton(
                            enabled = name.isNotBlank(),
                            onClick = {
                                onSave(
                                    Place(
                                        id = initial?.id ?: 0,
                                        name = name.trim(),
                                        latitude = center.latitude,
                                        longitude = center.longitude,
                                        lastUsedAt = initial?.lastUsedAt ?: 0,
                                    ),
                                )
                            },
                            modifier = Modifier.testTag(TestTags.PLACE_SAVE),
                        ) { Text(stringResource(R.string.save)) }
                    },
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                    label = { Text(stringResource(R.string.places_name)) },
                    placeholder = { Text(stringResource(R.string.places_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.PLACE_NAME),
                )
                Text(
                    stringResource(R.string.places_map_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalDcColors.current.textSecondary,
                )
                Box(modifier = Modifier.fillMaxWidth().height(MAP_HEIGHT), contentAlignment = Alignment.Center) {
                    MapLibreView(
                        markers = emptyList(),
                        userLatitude = position?.first,
                        userLongitude = position?.second,
                        onMarkerClick = {},
                        onCameraChanged = { center = it },
                        clusterMarkers = false,
                        persistedCameraState = initialCamera,
                        focusLocationRequestId = focusRequest,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // The pin's tip marks the middle of the map.
                    Icon(
                        Icons.Default.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(PIN_SIZE).offset(y = -PIN_SIZE / 2),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        coordinatesText(center.latitude, center.longitude),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        locationUnavailable = false
                        if (context.hasLocationPermission()) {
                            locate()
                        } else {
                            permissionLauncher.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                            )
                        }
                    }) {
                        Icon(Icons.Default.MyLocation, contentDescription = null)
                        Text(stringResource(R.string.places_my_position), modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (locationUnavailable) {
                    Text(
                        stringResource(R.string.places_position_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

internal fun coordinatesText(latitude: Double, longitude: Double): String = String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude)

private val MAP_HEIGHT = 320.dp
private val PIN_SIZE = 40.dp
private const val MAX_NAME_LENGTH = 60
