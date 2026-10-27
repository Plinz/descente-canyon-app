package fr.descentecanyon.app.ui.outing

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HolidayVillage
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.FoundPlace
import fr.descentecanyon.app.domain.model.FoundPlaceKind
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.repository.PlaceSearchRepository
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.location.hasLocationPermission
import fr.descentecanyon.app.ui.location.loadCurrentDeviceLocation
import fr.descentecanyon.app.ui.map.MapCameraState
import fr.descentecanyon.app.ui.map.MapFocusRequest
import fr.descentecanyon.app.ui.map.MapLibreView
import fr.descentecanyon.app.ui.test.TestTags
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaceSearchUiState(
    val query: String = "",
    val results: List<FoundPlace> = emptyList(),
    /** The text [results] answer: the search of the text typed comes a moment later. */
    val searchedQuery: String = "",
    /** The last search got no answer (no network, service down). */
    val failed: Boolean = false,
) {
    val isLongEnough: Boolean get() = query.trim().length >= PlaceSearchViewModel.MIN_QUERY_LENGTH
    val isSearching: Boolean get() = isLongEnough && query != searchedQuery
}

/** Finds a place by its name or address while the user types, around the area seen on the map. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlaceSearchViewModel @Inject constructor(private val placeSearchRepository: PlaceSearchRepository) : ViewModel() {

    private data class Request(val text: String, val near: MapCameraState, val language: String, val now: Boolean = false, val id: Int = 0)

    private val requests = MutableStateFlow<Request?>(null)
    private val _uiState = MutableStateFlow(PlaceSearchUiState())
    val uiState: StateFlow<PlaceSearchUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            requests.filterNotNull()
                .debounce { if (it.now) 0L else SEARCH_DEBOUNCE_MS }
                .mapLatest { request -> request to search(request) }
                .collect { (request, result) ->
                    _uiState.update {
                        it.copy(results = result.getOrDefault(emptyList()), searchedQuery = request.text, failed = result.isFailure)
                    }
                }
        }
    }

    /** The text goes to the state at once, the search waits for a pause in the typing. */
    fun updateQuery(value: String, near: MapCameraState, language: String) {
        _uiState.update { it.copy(query = value) }
        requests.value = Request(value, near, language)
    }

    /** The search key of the keyboard: no waiting, and a failed search is tried again. */
    fun searchNow(near: MapCameraState, language: String) {
        val state = _uiState.value
        if (!state.failed && state.searchedQuery == state.query) return
        requests.update { Request(state.query, near, language, now = true, id = (it?.id ?: 0) + 1) }
    }

    fun clear() {
        requests.value = null
        _uiState.value = PlaceSearchUiState()
    }

    private suspend fun search(request: Request): Result<List<FoundPlace>> {
        if (request.text.trim().length < MIN_QUERY_LENGTH) return Result.success(emptyList())
        return placeSearchRepository.search(request.text, request.near.latitude, request.near.longitude, request.language)
    }

    companion object {
        const val MIN_QUERY_LENGTH = 3
        private const val SEARCH_DEBOUNCE_MS = 500L
    }
}

/**
 * A place: found by its name or address, or placed by hand under the pin at the middle of the map,
 * and its name. The map fills the screen so it moves freely.
 */
@Composable
internal fun PlaceEditor(
    initial: Place?,
    initialCamera: MapCameraState,
    onSave: (Place) -> Unit,
    onDismiss: () -> Unit,
    searchViewModel: PlaceSearchViewModel = hiltViewModel(key = PLACE_SEARCH_KEY),
) {
    val context = LocalContext.current
    val colors = LocalDcColors.current
    val language = LocalLocale.current.platformLocale.language
    val search by searchViewModel.uiState.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    // The name given by the last place picked: picking another one replaces it, a name typed stays.
    var pickedName by rememberSaveable { mutableStateOf<String?>(null) }
    var center by remember { mutableStateOf(initialCamera) }
    var position by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var locationRequest by remember { mutableIntStateOf(0) }
    var focus by remember { mutableStateOf<MapFocusRequest?>(null) }
    var resultsShown by remember { mutableStateOf(false) }
    var locationUnavailable by remember { mutableStateOf(false) }
    DisposableEffect(searchViewModel) { onDispose { searchViewModel.clear() } }

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

    fun pick(found: FoundPlace) {
        center = MapCameraState(found.latitude, found.longitude, FOUND_PLACE_ZOOM)
        focus = MapFocusRequest((focus?.id ?: 0) + 1, found.latitude, found.longitude, FOUND_PLACE_ZOOM)
        if (name.isBlank() || name == pickedName) {
            name = found.name.take(MAX_NAME_LENGTH)
            pickedName = name
        }
        resultsShown = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The dialog's own window holds the focus of the search field: its keyboard closes from here.
        val focusManager = LocalFocusManager.current
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
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding()) {
                OutlinedTextField(
                    value = search.query,
                    onValueChange = {
                        searchViewModel.updateQuery(it, center, language)
                        resultsShown = true
                    },
                    placeholder = { Text(stringResource(R.string.places_search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        when {
                            search.isSearching -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)

                            search.query.isNotEmpty() -> IconButton(onClick = searchViewModel::clear) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.places_search_clear))
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        resultsShown = true
                        searchViewModel.searchNow(center, language)
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag(TestTags.PLACE_SEARCH),
                )
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    MapLibreView(
                        markers = emptyList(),
                        userLatitude = position?.first,
                        userLongitude = position?.second,
                        onMarkerClick = {},
                        onMapInteraction = { resultsShown = false },
                        onCameraChanged = { center = it },
                        onUserCameraMove = { resultsShown = false },
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
                    if (resultsShown && search.isLongEnough && !search.isSearching) {
                        SearchResults(
                            search = search,
                            onPick = { found ->
                                pick(found)
                                focusManager.clearFocus()
                            },
                            modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
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
                        if (locationUnavailable) {
                            stringResource(R.string.places_position_unavailable)
                        } else {
                            stringResource(R.string.places_map_hint, coordinatesText(center.latitude, center.longitude))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (locationUnavailable) MaterialTheme.colorScheme.error else colors.textSecondary,
                    )
                }
            }
        }
    }
}

/** The places found, over the top of the map; a tap moves the map there. */
@Composable
private fun SearchResults(search: PlaceSearchUiState, onPick: (FoundPlace) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDcColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.backgroundElevated,
        shadowElevation = 6.dp,
    ) {
        when {
            search.failed -> ResultMessage(stringResource(R.string.places_search_failed))

            search.results.isEmpty() -> ResultMessage(stringResource(R.string.places_search_empty))

            else -> Column {
                LazyColumn(modifier = Modifier.heightIn(max = RESULTS_MAX_HEIGHT).testTag(TestTags.PLACE_SEARCH_RESULTS)) {
                    itemsIndexed(search.results) { index, found ->
                        if (index > 0) HorizontalDivider(color = colors.borderSubtle)
                        FoundPlaceRow(found = found, onClick = { onPick(found) })
                    }
                }
                Text(
                    stringResource(R.string.places_search_attribution),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ResultMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalDcColors.current.textSecondary,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun FoundPlaceRow(found: FoundPlace, onClick: () -> Unit) {
    val colors = LocalDcColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(found.kind.icon, contentDescription = null, tint = colors.textSecondary)
        Column(modifier = Modifier.weight(1f)) {
            Text(found.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            val kind = when (found.kind) {
                FoundPlaceKind.CAMPSITE -> stringResource(R.string.places_kind_campsite)
                FoundPlaceKind.LODGING -> stringResource(R.string.places_kind_lodging)
                else -> null
            }
            val detail = listOfNotNull(kind, found.detail.ifBlank { null }).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
        }
    }
}

private val FoundPlaceKind.icon: ImageVector
    get() = when (this) {
        FoundPlaceKind.CAMPSITE -> Icons.Default.HolidayVillage
        FoundPlaceKind.LODGING -> Icons.Default.Hotel
        FoundPlaceKind.LOCALITY -> Icons.Default.LocationCity
        FoundPlaceKind.ADDRESS -> Icons.Default.Home
        FoundPlaceKind.OTHER -> Icons.Default.Place
    }

private const val PLACE_SEARCH_KEY = "place-search"
private const val FOUND_PLACE_ZOOM = 16.0
private const val MAX_NAME_LENGTH = 60
private val PIN_SIZE = 40.dp
private val RESULTS_MAX_HEIGHT = 300.dp
