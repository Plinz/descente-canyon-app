package fr.descentecanyon.app.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.coroutines.DefaultDispatcher
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.DriveTime
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.model.SearchCriteria
import fr.descentecanyon.app.domain.model.SearchResultSet
import fr.descentecanyon.app.domain.model.SearchSortField
import fr.descentecanyon.app.domain.model.SortDirection
import fr.descentecanyon.app.domain.model.ZoneCountry
import fr.descentecanyon.app.domain.repository.RecentCanyonsRepository
import fr.descentecanyon.app.domain.usecase.ChooseStayCanyonUseCase
import fr.descentecanyon.app.domain.usecase.SearchCanyonsUseCase
import fr.descentecanyon.app.domain.usecase.driveTo
import fr.descentecanyon.app.perf.PerformanceTrace
import fr.descentecanyon.app.ui.common.UiText
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
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

/**
 * The search opened for a day of a stay: the canyons around its place, nearest first, each with the
 * drive to it and the other day of the stay it is planned for. The canyon page then plans it for that day.
 */
data class SearchStayPick(
    val stayId: Long,
    val date: LocalDate,
    /** The outing of the day whose canyon is changed, 0 when the day gets its first one. */
    val outingId: Long,
    val place: Place,
    /** Drives kept or found by road, by canyon id; the others are estimated. */
    val drives: Map<Int, DriveTime> = emptyMap(),
    /** The other day of the stay each canyon is planned for. */
    val plannedOn: Map<Int, LocalDate> = emptyMap(),
) {
    fun driveTo(item: CanyonSearchItem): DriveTime? = driveTo(place, item, drives)
}

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
    private val chooseStayCanyonUseCase: ChooseStayCanyonUseCase,
) : ViewModel() {

    /** The day of a stay the search was opened for, if any (route arguments). */
    private val stayId: Long = savedStateHandle[STAY_ID_KEY] ?: 0L
    private val stayDate: LocalDate? = savedStateHandle.get<String>(STAY_DATE_KEY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    private val stayOutingId: Long = savedStateHandle[STAY_OUTING_ID_KEY] ?: 0L

    /** Opened for a day of a stay: distances from its place, not from the user. */
    val isStayPick: Boolean = stayId > 0 && stayDate != null

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
        stayDate?.takeIf { isStayPick }?.let { date ->
            viewModelScope.launch {
                // Distances from the place of the stay: the GPS position is not asked.
                val place = chooseStayCanyonUseCase.observe(stayId, date).mapNotNull { it?.place }.first()
                locationFlow.update { it.copy(userLatitude = place.latitude, userLongitude = place.longitude) }
            }
        }
        viewModelScope.launch {
            combine(
                searchCanyonsUseCase.observeCatalog(),
                criteriaInputFlow,
                // For a stay, nothing is listed before its place is known: the canyons around it come first.
                if (isStayPick) locationFlow.filter { it.userLatitude != null } else locationFlow,
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
                    val resultSet = searchCanyonsUseCase(input.catalog, input.criteria).let {
                        if (isStayPick) it.withoutForbidden() else it
                    }
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

    /** The day of the stay the search was opened for, with the drives to the canyons listed; null otherwise. */
    val stayPick: StateFlow<SearchStayPick?> = stayPickFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private fun stayPickFlow(): Flow<SearchStayPick?> {
        val date = stayDate?.takeIf { isStayPick } ?: return flowOf(null)
        val choice = chooseStayCanyonUseCase.observe(stayId, date)
        // The place by its position: marking it used must not ask the routes again.
        val drives = choice.map { it?.place?.copy(lastUsedAt = 0) }.distinctUntilChanged().flatMapLatest { place ->
            place ?: return@flatMapLatest flowOf(emptyMap())
            chooseStayCanyonUseCase.observeDrives(place, uiState.map { it.results }.distinctUntilChanged())
        }
        return combine(choice, drives) { choice, drives ->
            choice?.let { SearchStayPick(stayId, date, stayOutingId, it.place, drives, it.plannedOn) }
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

    /** As left on this screen; for a stay, the canyons around its place by default, nearest first. */
    private fun loadCriteria(): SearchCriteria {
        val raw = savedStateHandle.get<String>(SEARCH_CRITERIA_KEY).orEmpty()
        return runCatching { json.decodeFromString<SearchCriteria>(raw) }.getOrNull() ?: if (isStayPick) {
            SearchCriteria(
                maxDistanceKm = ChooseStayCanyonUseCase.DEFAULT_DISTANCE_KM,
                sortField = SearchSortField.DISTANCE,
                sortDirection = SortDirection.ASC,
            )
        } else {
            SearchCriteria()
        }
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
        /** Route arguments of the search opened for a day of a stay. */
        private const val STAY_ID_KEY = "stayId"
        private const val STAY_DATE_KEY = "date"
        private const val STAY_OUTING_ID_KEY = "outingId"
        private const val STOP_TIMEOUT_MS = 5_000L
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

/** A forbidden canyon cannot be planned for a day. */
private fun SearchResultSet.withoutForbidden(): SearchResultSet {
    if (isResultListDeferred) return this
    val allowed = results.filterNot { it.isForbidden }
    return copy(results = allowed, totalResultsCount = allowed.size)
}

private fun SearchCriteria.sanitizedForPersistence(): SearchCriteria = copy(
    selectedDepartment = selectedDepartment.takeIf { selectedCountry != null },
    userLatitude = null,
    userLongitude = null,
)

private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000

private const val RECENT_CANYONS_SHOWN = 10
