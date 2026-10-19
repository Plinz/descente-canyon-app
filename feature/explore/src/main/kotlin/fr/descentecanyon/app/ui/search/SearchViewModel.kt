package fr.descentecanyon.app.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.coroutines.DefaultDispatcher
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.SearchCriteria
import fr.descentecanyon.app.domain.model.SearchResultSet
import fr.descentecanyon.app.domain.model.SearchSortField
import fr.descentecanyon.app.domain.model.SortDirection
import fr.descentecanyon.app.domain.model.ZoneCountry
import fr.descentecanyon.app.domain.repository.RecentCanyonsRepository
import fr.descentecanyon.app.domain.usecase.SearchCanyonsUseCase
import fr.descentecanyon.app.perf.PerformanceTrace
import fr.descentecanyon.app.ui.common.UiText
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class SearchResultViewMode {
    LIST,
    MAP,
}

data class SearchLocationUiState(
    val hasLocationPermission: Boolean = false,
    val hasRequestedLocationPermission: Boolean = false,
    val userLatitude: Double? = null,
    val userLongitude: Double? = null,
    val isLocating: Boolean = false,
)

data class SearchUiState(
    val queryDraft: String = "",
    val criteria: SearchCriteria = SearchCriteria(),
    val results: List<CanyonSearchItem> = emptyList(),
    val isLoading: Boolean = true,
    val isSearching: Boolean = false,
    val error: UiText? = null,
    val resultViewMode: SearchResultViewMode = SearchResultViewMode.LIST,
    val zones: List<ZoneCountry> = emptyList(),
    val activeFilterCount: Int = 0,
    val totalResultsCount: Int = 0,
    val isResultListDeferred: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val hasRequestedLocationPermission: Boolean = false,
    val userLatitude: Double? = null,
    val userLongitude: Double? = null,
    val isLocating: Boolean = false,
    val selectedCanyon: CanyonSearchItem? = null,
    val scrollResetRequestId: Int = 0,
    /** Shown while no query or filter narrows the catalog. */
    val recentCanyons: List<CanyonSummary> = emptyList(),
    /** Canyons of the user's logbook. */
    val descendedIds: Set<Int> = emptySet(),
)

@HiltViewModel
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel @Inject constructor(
    private val searchCanyonsUseCase: SearchCanyonsUseCase,
    private val savedStateHandle: SavedStateHandle,
    @param:DefaultDispatcher private val searchDispatcher: CoroutineDispatcher,
    private val recentCanyonsRepository: RecentCanyonsRepository,
) : ViewModel() {

    private val initialCriteria = loadCriteria()
    private val initialResultViewMode = loadResultViewMode()
    private val criteriaInputFlow = MutableStateFlow(
        SearchCriteriaInputState(
            filters = initialCriteria.sanitizedForPersistence().copy(query = ""),
            appliedQuery = initialCriteria.query,
        ),
    )
    private val queryDraftFlow = MutableStateFlow(initialCriteria.query)
    private val locationFlow = MutableStateFlow(SearchLocationUiState())
    private val resultViewModeFlow = MutableStateFlow(initialResultViewMode)
    private val selectedCanyonIdFlow = MutableStateFlow<Int?>(null)
    private var queryDebounceJob: Job? = null
    private var hasLoggedInitialResults = false

    private val _uiState = MutableStateFlow(
        SearchUiState(
            queryDraft = initialCriteria.query,
            criteria = initialCriteria,
            activeFilterCount = initialCriteria.activeFilterCount(),
            resultViewMode = initialResultViewMode,
        ),
    )
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        PerformanceTrace.start(SEARCH_INITIAL_TRACE_KEY, "search_initial_load")
        viewModelScope.launch {
            combine(
                searchCanyonsUseCase.observeCatalog(),
                criteriaInputFlow,
                locationFlow,
            ) { catalog, criteriaInput, location ->
                SearchQueryInput(
                    catalog = catalog,
                    criteria = criteriaInput.filters.copy(
                        query = criteriaInput.appliedQuery,
                        userLatitude = location.userLatitude,
                        userLongitude = location.userLongitude,
                    ),
                    location = location,
                    scrollResetRequestId = criteriaInput.scrollResetRequestId,
                )
            }
                .mapLatest { input ->
                    val computationStartedAt = monotonicNowMs()
                    val resultSet = searchCanyonsUseCase(input.catalog, input.criteria)
                    PerformanceTrace.logEvent(
                        event = "search_compute",
                        "catalogSize" to input.catalog.size,
                        "resultCount" to resultSet.results.size,
                        "queryLength" to input.criteria.query.length,
                        "activeFilters" to input.criteria.activeFilterCount(),
                        "sortField" to input.criteria.sortField,
                        "sortDirection" to input.criteria.sortDirection,
                        "durationMs" to (monotonicNowMs() - computationStartedAt),
                    )
                    SearchComputedState(
                        criteria = input.criteria,
                        location = input.location,
                        resultSet = resultSet,
                        scrollResetRequestId = input.scrollResetRequestId,
                        descendedIds = input.catalog.mapNotNullTo(mutableSetOf()) { item -> item.id.takeIf { item.isDescended } },
                    )
                }
                .combine(resultViewModeFlow) { computed, resultViewMode ->
                    computed to resultViewMode
                }
                .combine(selectedCanyonIdFlow) { (computed, resultViewMode), selectedCanyonId ->
                    Triple(computed, resultViewMode, selectedCanyonId)
                }
                .combine(recentCanyonsRepository.observeRecentCanyons(RECENT_CANYONS_SHOWN)) {
                        (computed, resultViewMode, selectedCanyonId),
                        recentCanyons,
                    ->
                    SearchUiState(
                        queryDraft = queryDraftFlow.value,
                        criteria = computed.criteria,
                        results = computed.resultSet.results,
                        isLoading = false,
                        isSearching = false,
                        error = null,
                        resultViewMode = resultViewMode,
                        zones = computed.resultSet.zones,
                        activeFilterCount = computed.criteria.activeFilterCount(),
                        totalResultsCount = computed.resultSet.totalResultsCount,
                        isResultListDeferred = computed.resultSet.isResultListDeferred,
                        hasLocationPermission = computed.location.hasLocationPermission,
                        hasRequestedLocationPermission = computed.location.hasRequestedLocationPermission,
                        userLatitude = computed.location.userLatitude,
                        userLongitude = computed.location.userLongitude,
                        isLocating = computed.location.isLocating,
                        selectedCanyon = computed.resultSet.results.firstOrNull { it.id == selectedCanyonId },
                        scrollResetRequestId = computed.scrollResetRequestId,
                        recentCanyons = recentCanyons,
                        descendedIds = computed.descendedIds,
                    )
                }
                .flowOn(searchDispatcher)
                .catch { throwable ->
                    if (!hasLoggedInitialResults) {
                        hasLoggedInitialResults = true
                        PerformanceTrace.end(
                            key = SEARCH_INITIAL_TRACE_KEY,
                            outcome = "failed",
                            "error" to (throwable.message ?: throwable::class.simpleName),
                        )
                    }
                    val criteriaInput = criteriaInputFlow.value
                    _uiState.value = SearchUiState(
                        queryDraft = queryDraftFlow.value,
                        criteria = criteriaInput.filters.copy(
                            query = criteriaInput.appliedQuery,
                            userLatitude = locationFlow.value.userLatitude,
                            userLongitude = locationFlow.value.userLongitude,
                        ),
                        isLoading = false,
                        isSearching = false,
                        error = UiText.res(R.string.search_catalog_load_error),
                        resultViewMode = resultViewModeFlow.value,
                        hasLocationPermission = locationFlow.value.hasLocationPermission,
                        hasRequestedLocationPermission = locationFlow.value.hasRequestedLocationPermission,
                        userLatitude = locationFlow.value.userLatitude,
                        userLongitude = locationFlow.value.userLongitude,
                        isLocating = locationFlow.value.isLocating,
                        scrollResetRequestId = criteriaInput.scrollResetRequestId,
                    )
                }
                .collect { state ->
                    if (!hasLoggedInitialResults) {
                        hasLoggedInitialResults = true
                        PerformanceTrace.end(
                            key = SEARCH_INITIAL_TRACE_KEY,
                            outcome = "ready",
                            "totalResultsCount" to state.totalResultsCount,
                            "resultCount" to state.results.size,
                            "viewMode" to state.resultViewMode,
                        )
                    }
                    _uiState.update { current -> state.copy(error = current.error ?: state.error) }
                }
        }
    }

    fun clearRecentCanyons() {
        viewModelScope.launch { recentCanyonsRepository.clear() }
    }

    fun onQueryChanged(query: String) {
        val previousDraft = queryDraftFlow.value
        queryDraftFlow.value = query
        applyQuery(query = query, immediate = query.length <= previousDraft.length)
        selectedCanyonIdFlow.value = null
        _uiState.update {
            it.copy(queryDraft = query, isSearching = query != criteriaInputFlow.value.appliedQuery)
        }
    }

    fun onCriteriaChanged(criteria: SearchCriteria) {
        val current = criteriaInputFlow.value.filters
        val sanitized = criteria.sanitizedForPersistence()
        val updated = if (!current.selectedCountry.equals(sanitized.selectedCountry, ignoreCase = true) &&
            current.selectedDepartment == sanitized.selectedDepartment
        ) {
            sanitized.copy(selectedDepartment = null)
        } else {
            sanitized
        }
        criteriaInputFlow.update {
            it.copy(
                filters = updated.copy(query = ""),
                scrollResetRequestId = it.scrollResetRequestId + 1,
            )
        }
        selectedCanyonIdFlow.value = null
        persistCriteria(updated)
        _uiState.update {
            it.copy(
                criteria = updated.copy(
                    query = queryDraftFlow.value,
                    userLatitude = locationFlow.value.userLatitude,
                    userLongitude = locationFlow.value.userLongitude,
                ),
                activeFilterCount = updated.activeFilterCount(),
                isSearching = true,
            )
        }
    }

    /** A new field starts in its natural order (A to Z, nearest, best first); the current one is kept as is. */
    fun onSortSelected(field: SearchSortField) {
        val current = criteriaInputFlow.value.filters
        if (current.sortField == field) return
        _uiState.update { it.copy(error = null) }
        onCriteriaChanged(current.copy(sortField = field, sortDirection = field.defaultDirection()))
    }

    fun onSortDirectionSelected(direction: SortDirection) {
        val current = criteriaInputFlow.value.filters
        if (current.sortDirection == direction) return
        _uiState.update { it.copy(error = null) }
        onCriteriaChanged(current.copy(sortDirection = direction))
    }

    fun onResultViewModeChanged(mode: SearchResultViewMode) {
        resultViewModeFlow.value = mode
        persistResultViewMode(mode)
        if (mode == SearchResultViewMode.LIST) selectedCanyonIdFlow.value = null
    }

    fun onLocationPermissionResult(granted: Boolean) {
        locationFlow.update {
            it.copy(
                hasLocationPermission = granted,
                hasRequestedLocationPermission = true,
                isLocating = false,
            )
        }
        if (granted) {
            _uiState.update { it.copy(error = null) }
        }
    }

    fun onLocationLookupStarted() {
        locationFlow.update { it.copy(isLocating = true) }
    }

    fun onUserLocationUpdated(latitude: Double, longitude: Double) {
        locationFlow.update {
            it.copy(userLatitude = latitude, userLongitude = longitude, isLocating = false)
        }
        _uiState.update { it.copy(error = null) }
    }

    fun onLocationUnavailable() {
        locationFlow.update { it.copy(isLocating = false) }
        _uiState.update {
            it.copy(error = UiText.res(R.string.location_recent_unavailable))
        }
    }

    fun selectCanyon(canyonId: Int) {
        selectedCanyonIdFlow.value = canyonId
    }

    fun clearSelectedCanyon() {
        selectedCanyonIdFlow.value = null
    }

    fun clearAllFilters() = onCriteriaChanged(criteriaInputFlow.value.filters.clearAllFilters())
    fun clearCountry() = onCriteriaChanged(criteriaInputFlow.value.filters.copy(selectedCountry = null, selectedDepartment = null))
    fun clearDepartment() = onCriteriaChanged(criteriaInputFlow.value.filters.copy(selectedDepartment = null))

    fun clearQuery() {
        queryDraftFlow.value = ""
        applyQuery(query = "", immediate = true)
        selectedCanyonIdFlow.value = null
        _uiState.update { it.copy(queryDraft = "", isSearching = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun applyQuery(query: String, immediate: Boolean) {
        queryDebounceJob?.cancel()
        if (immediate) {
            updateAppliedQuery(query)
            return
        }
        queryDebounceJob = viewModelScope.launch {
            delay(200)
            updateAppliedQuery(query)
        }
    }

    private fun updateAppliedQuery(query: String) {
        if (criteriaInputFlow.value.appliedQuery == query) {
            return
        }
        criteriaInputFlow.update {
            it.copy(
                appliedQuery = query,
                scrollResetRequestId = it.scrollResetRequestId + 1,
            )
        }
    }

    private fun loadCriteria(): SearchCriteria {
        val raw = savedStateHandle.get<String>(SEARCH_CRITERIA_KEY).orEmpty()
        return runCatching { json.decodeFromString<SearchCriteria>(raw) }.getOrDefault(SearchCriteria())
    }

    private fun persistCriteria(criteria: SearchCriteria) {
        savedStateHandle[SEARCH_CRITERIA_KEY] = json.encodeToString(criteria.sanitizedForPersistence())
    }

    private fun loadResultViewMode(): SearchResultViewMode {
        val raw = savedStateHandle.get<String>(SEARCH_RESULT_VIEW_MODE_KEY).orEmpty()
        return raw.takeIf { it.isNotBlank() }
            ?.let { value -> SearchResultViewMode.entries.firstOrNull { it.name == value } }
            ?: SearchResultViewMode.LIST
    }

    private fun persistResultViewMode(mode: SearchResultViewMode) {
        savedStateHandle[SEARCH_RESULT_VIEW_MODE_KEY] = mode.name
    }

    private fun SearchSortField.defaultDirection(): SortDirection = when (this) {
        SearchSortField.NAME, SearchSortField.DISTANCE -> SortDirection.ASC
        else -> SortDirection.DESC
    }

    companion object {
        private const val SEARCH_CRITERIA_KEY = "search_criteria"
        private const val SEARCH_RESULT_VIEW_MODE_KEY = "search_result_view_mode"
        private const val SEARCH_INITIAL_TRACE_KEY = "screen.search.initial"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

private data class SearchCriteriaInputState(val filters: SearchCriteria, val appliedQuery: String, val scrollResetRequestId: Int = 0)

private data class SearchQueryInput(
    val catalog: List<CanyonSearchItem>,
    val criteria: SearchCriteria,
    val location: SearchLocationUiState,
    val scrollResetRequestId: Int,
)

private data class SearchComputedState(
    val criteria: SearchCriteria,
    val location: SearchLocationUiState,
    val resultSet: SearchResultSet,
    val scrollResetRequestId: Int,
    val descendedIds: Set<Int>,
)

private fun SearchCriteria.sanitizedForPersistence(): SearchCriteria = copy(
    selectedDepartment = selectedDepartment.takeIf { selectedCountry != null },
    userLatitude = null,
    userLongitude = null,
)

private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000

private const val RECENT_CANYONS_SHOWN = 10
