package fr.descentecanyon.app.ui.map

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.SearchCriteria
import fr.descentecanyon.app.domain.model.toSummary
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.domain.usecase.SearchCanyonsUseCase
import fr.descentecanyon.app.domain.usecase.ToggleFavoriteUseCase
import fr.descentecanyon.app.perf.PerformanceTrace
import fr.descentecanyon.app.ui.common.UiText
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class MapUiState(
    val mapCanyons: List<CanyonSummary> = emptyList(),
    val selectedCanyon: CanyonSummary? = null,
    val isSelectedCanyonFavorite: Boolean = false,
    val isLoading: Boolean = true,
    val error: UiText? = null,
    val transientMessage: UiText? = null,
    val hasLocationPermission: Boolean = false,
    val hasRequestedLocationPermission: Boolean = false,
    val isLocating: Boolean = false,
    val userLatitude: Double? = null,
    val userLongitude: Double? = null,
    val cameraState: MapCameraState? = null,
    val focusLocationRequestId: Int = 0,
    val criteria: SearchCriteria = SearchCriteria(),
    val availableCountries: List<String> = emptyList(),
    val availableDepartments: List<String> = emptyList(),
    val totalResultsCount: Int = 0,
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    searchCanyonsUseCase: SearchCanyonsUseCase,
    private val favoritesRepository: FavoritesRepository,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
) : ViewModel() {

    private val initialCriteria = loadCriteria()
    private val criteriaFlow = MutableStateFlow(initialCriteria)
    private val _uiState = MutableStateFlow(
        MapUiState(
            hasLocationPermission = savedStateHandle[MAP_HAS_LOCATION_PERMISSION_KEY] ?: false,
            hasRequestedLocationPermission = savedStateHandle[MAP_HAS_REQUESTED_LOCATION_PERMISSION_KEY] ?: false,
            userLatitude = savedStateHandle.get<Double>(MAP_USER_LATITUDE_KEY),
            userLongitude = savedStateHandle.get<Double>(MAP_USER_LONGITUDE_KEY),
            cameraState = loadCameraState(savedStateHandle),
            focusLocationRequestId = savedStateHandle[MAP_FOCUS_LOCATION_REQUEST_ID_KEY] ?: 0,
            criteria = initialCriteria,
        ),
    )
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()
    private var hasLoggedInitialCatalog = false
    private var favoriteCanyonIds = emptySet<Int>()
    private val restoredSelectedCanyonId = savedStateHandle.get<Int>(MAP_SELECTED_CANYON_ID_KEY)

    init {
        PerformanceTrace.start(MAP_INITIAL_TRACE_KEY, "map_initial_load")
        viewModelScope.launch {
            favoritesRepository.getFavorites().collect { favorites ->
                favoriteCanyonIds = favorites.mapTo(mutableSetOf()) { it.id }
                _uiState.update { state ->
                    state.copy(isSelectedCanyonFavorite = state.selectedCanyon?.id?.let(favoriteCanyonIds::contains) == true)
                }
            }
        }
        viewModelScope.launch {
            searchCanyonsUseCase.observeCatalog()
                .combine(criteriaFlow) { catalog, criteria ->
                    val resultSet = searchCanyonsUseCase(catalog, criteria)
                    val matchingCanyons = if (resultSet.isResultListDeferred) catalog else resultSet.results
                    MapCatalogState(
                        mapCanyons = matchingCanyons.mapNotNull { item ->
                            item.takeIf { it.representativeLat != null && it.representativeLng != null }?.toSummary()
                        },
                        criteria = criteria,
                        availableCountries = resultSet.availableCountries,
                        availableDepartments = resultSet.availableDepartments,
                        totalResultsCount = resultSet.totalResultsCount,
                        catalogSize = catalog.size,
                    )
                }
                .catch { throwable ->
                    if (!hasLoggedInitialCatalog) {
                        hasLoggedInitialCatalog = true
                        PerformanceTrace.end(
                            key = MAP_INITIAL_TRACE_KEY,
                            outcome = "failed",
                            "error" to (throwable.message ?: throwable::class.simpleName),
                        )
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = UiText.res(R.string.map_load_error),
                        )
                    }
                }
                .collect { catalogState ->
                    val mappingStartedAt = monotonicNowMs()
                    PerformanceTrace.logEvent(
                        event = "map_catalog_compute",
                        "catalogSize" to catalogState.catalogSize,
                        "markerCount" to catalogState.mapCanyons.size,
                        "activeFilters" to catalogState.criteria.activeFilterCount(),
                        "durationMs" to (monotonicNowMs() - mappingStartedAt),
                    )
                    _uiState.update { state ->
                        val selectedCanyon = (state.selectedCanyon?.id ?: restoredSelectedCanyonId)?.let { selectedId ->
                            catalogState.mapCanyons.firstOrNull { canyon -> canyon.id == selectedId }
                        }
                        state.copy(
                            mapCanyons = catalogState.mapCanyons,
                            selectedCanyon = selectedCanyon,
                            isSelectedCanyonFavorite = selectedCanyon?.id?.let(favoriteCanyonIds::contains) == true,
                            isLoading = false,
                            criteria = catalogState.criteria,
                            availableCountries = catalogState.availableCountries,
                            availableDepartments = catalogState.availableDepartments,
                            totalResultsCount = catalogState.totalResultsCount,
                        )
                    }
                    if (!hasLoggedInitialCatalog) {
                        hasLoggedInitialCatalog = true
                        PerformanceTrace.end(
                            key = MAP_INITIAL_TRACE_KEY,
                            outcome = "ready",
                            "catalogSize" to catalogState.catalogSize,
                            "markerCount" to catalogState.mapCanyons.size,
                        )
                    }
                }
        }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                hasLocationPermission = granted,
                hasRequestedLocationPermission = true,
                error = null,
            )
        }
        savedStateHandle[MAP_HAS_LOCATION_PERMISSION_KEY] = granted
        savedStateHandle[MAP_HAS_REQUESTED_LOCATION_PERMISSION_KEY] = true
    }

    fun onLocationLookupStarted() {
        _uiState.update { it.copy(isLocating = true, error = null, transientMessage = null) }
    }

    fun focusAroundUser(latitude: Double, longitude: Double) {
        _uiState.update {
            it.copy(
                error = null,
                transientMessage = null,
                isLocating = false,
                userLatitude = latitude,
                userLongitude = longitude,
                focusLocationRequestId = it.focusLocationRequestId + 1,
            )
        }
        savedStateHandle[MAP_USER_LATITUDE_KEY] = latitude
        savedStateHandle[MAP_USER_LONGITUDE_KEY] = longitude
        savedStateHandle[MAP_FOCUS_LOCATION_REQUEST_ID_KEY] = _uiState.value.focusLocationRequestId
    }

    fun onCameraChanged(cameraState: MapCameraState) {
        _uiState.update { state ->
            if (state.cameraState.isSameAs(cameraState)) {
                state
            } else {
                state.copy(cameraState = cameraState)
            }
        }
        persistCameraState(cameraState)
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun onLocationUnavailable() {
        _uiState.update {
            it.copy(
                isLocating = false,
                transientMessage = UiText.res(R.string.location_recent_unavailable),
            )
        }
    }

    fun selectCanyon(canyonId: Int) {
        _uiState.update { state ->
            val selectedCanyon = state.mapCanyons.firstOrNull { it.id == canyonId }
            state.copy(
                selectedCanyon = selectedCanyon,
                isSelectedCanyonFavorite = selectedCanyon?.id?.let(favoriteCanyonIds::contains) == true,
            )
        }
        savedStateHandle[MAP_SELECTED_CANYON_ID_KEY] = canyonId
    }

    fun clearSelectedCanyon() {
        _uiState.update { it.copy(selectedCanyon = null, isSelectedCanyonFavorite = false) }
        savedStateHandle[MAP_SELECTED_CANYON_ID_KEY] = null
    }

    fun toggleSelectedCanyonFavorite() {
        val canyonId = _uiState.value.selectedCanyon?.id ?: return
        viewModelScope.launch {
            toggleFavoriteUseCase(canyonId)
        }
    }

    fun clearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    fun onCriteriaChanged(criteria: SearchCriteria) {
        val current = criteriaFlow.value
        val sanitized = criteria.sanitizedForMap()
        val updated = if (!current.selectedCountry.equals(sanitized.selectedCountry, ignoreCase = true) &&
            current.selectedDepartment == sanitized.selectedDepartment
        ) {
            sanitized.copy(selectedDepartment = null)
        } else {
            sanitized
        }
        criteriaFlow.value = updated
        persistCriteria(updated)
        _uiState.update { it.copy(criteria = updated, error = null) }
    }

    fun clearAllFilters() = onCriteriaChanged(criteriaFlow.value.clearAllFilters())

    private fun persistCameraState(cameraState: MapCameraState) {
        savedStateHandle[MAP_CAMERA_LATITUDE_KEY] = cameraState.latitude
        savedStateHandle[MAP_CAMERA_LONGITUDE_KEY] = cameraState.longitude
        savedStateHandle[MAP_CAMERA_ZOOM_KEY] = cameraState.zoom
    }

    private fun loadCriteria(): SearchCriteria {
        val raw = savedStateHandle.get<String>(MAP_CRITERIA_KEY).orEmpty()
        return runCatching { json.decodeFromString<SearchCriteria>(raw) }
            .getOrDefault(SearchCriteria())
            .sanitizedForMap()
    }

    private fun persistCriteria(criteria: SearchCriteria) {
        savedStateHandle[MAP_CRITERIA_KEY] = json.encodeToString(criteria.sanitizedForMap())
    }
}

private data class MapCatalogState(
    val mapCanyons: List<CanyonSummary>,
    val criteria: SearchCriteria,
    val availableCountries: List<String>,
    val availableDepartments: List<String>,
    val totalResultsCount: Int,
    val catalogSize: Int,
)

private const val MAP_INITIAL_TRACE_KEY = "screen.map.initial"
private const val MAP_HAS_LOCATION_PERMISSION_KEY = "map_has_location_permission"
private const val MAP_HAS_REQUESTED_LOCATION_PERMISSION_KEY = "map_has_requested_location_permission"
private const val MAP_USER_LATITUDE_KEY = "map_user_latitude"
private const val MAP_USER_LONGITUDE_KEY = "map_user_longitude"
private const val MAP_CAMERA_LATITUDE_KEY = "map_camera_latitude"
private const val MAP_CAMERA_LONGITUDE_KEY = "map_camera_longitude"
private const val MAP_CAMERA_ZOOM_KEY = "map_camera_zoom"
private const val MAP_SELECTED_CANYON_ID_KEY = "map_selected_canyon_id"
private const val MAP_FOCUS_LOCATION_REQUEST_ID_KEY = "map_focus_location_request_id"
private const val MAP_CRITERIA_KEY = "map_criteria"
private val json = Json { ignoreUnknownKeys = true }

private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000

private fun loadCameraState(savedStateHandle: SavedStateHandle): MapCameraState? {
    val latitude = savedStateHandle.get<Double>(MAP_CAMERA_LATITUDE_KEY) ?: return null
    val longitude = savedStateHandle.get<Double>(MAP_CAMERA_LONGITUDE_KEY) ?: return null
    val zoom = savedStateHandle.get<Double>(MAP_CAMERA_ZOOM_KEY) ?: return null
    return MapCameraState(latitude = latitude, longitude = longitude, zoom = zoom)
}

private fun MapCameraState?.isSameAs(other: MapCameraState): Boolean {
    if (this == null) return false
    return abs(latitude - other.latitude) < 0.000001 &&
        abs(longitude - other.longitude) < 0.000001 &&
        abs(zoom - other.zoom) < 0.01
}

private fun SearchCriteria.sanitizedForMap(): SearchCriteria = copy(
    query = "",
    userLatitude = null,
    userLongitude = null,
    selectedDepartment = selectedDepartment.takeIf { selectedCountry != null },
)
