package fr.descentecanyon.app.ui.outing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.DriveTime
import fr.descentecanyon.app.domain.model.DriveTimeSource
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.model.Shuttle
import fr.descentecanyon.app.domain.model.Stay
import fr.descentecanyon.app.domain.model.haversineKm
import fr.descentecanyon.app.domain.model.meetingParking
import fr.descentecanyon.app.domain.model.normalizeForSearch
import fr.descentecanyon.app.domain.model.toSummary
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.DriveTimeRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.PlaceRepository
import fr.descentecanyon.app.domain.repository.StayRepository
import fr.descentecanyon.app.ui.components.CanyonSummaryCard
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.DcDropdownMenu
import fr.descentecanyon.app.ui.design.DcDropdownMenuItem
import fr.descentecanyon.app.ui.design.DcEmptyState
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.test.TestTags
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which canyons around the place are offered. */
enum class StayFilter {
    ALL,
    FAVORITES,
    NOT_DESCENDED,
}

/** A canyon that could fill the day; [plannedOn] another day of the stay it is already planned for. */
data class StayCandidate(
    val canyon: CanyonSearchItem,
    val drive: DriveTime,
    val lastReport: Debit? = null,
    val plannedOn: LocalDate? = null,
)

data class StayCanyonPickerUiState(
    val isLoading: Boolean = true,
    val stay: Stay? = null,
    val place: Place? = null,
    val date: LocalDate,
    val today: LocalDate,
    /** The outing of the day whose canyon is changed, 0 when the day gets its first one. */
    val outingId: Long = 0,
    val radiusKm: Int = DEFAULT_RADIUS_KM,
    val filter: StayFilter = StayFilter.ALL,
    /** A name typed: the whole catalog is searched, whatever the distance. */
    val query: String = "",
    val candidates: List<StayCandidate> = emptyList(),
) {
    val isSearching: Boolean get() = query.normalizeForSearch().length >= MIN_QUERY_LENGTH

    companion object {
        const val DEFAULT_RADIUS_KM = 40
        val RADII_KM = listOf(20, 40, 60)
        const val MIN_QUERY_LENGTH = 2
    }
}

/**
 * The canyons around the place of the stay, nearest by road first, or those named, for one of its
 * days. The real drive to the nearest ones is asked once online: an estimate in a straight line
 * misleads where a ridge stands between the campsite and the canyon.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StayCanyonPickerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    stayRepository: StayRepository,
    placeRepository: PlaceRepository,
    private val canyonRepository: CanyonRepository,
    logbookRepository: LogbookRepository,
    outingRepository: OutingRepository,
    private val driveTimeRepository: DriveTimeRepository,
    private val debitRepository: DebitRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val clock: Clock,
) : ViewModel() {

    private val stayId: Long = savedStateHandle["stayId"] ?: 0L
    private val date: LocalDate = savedStateHandle.get<String>("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: LocalDate.now(clock)
    private val outingId: Long = savedStateHandle["outingId"] ?: 0L
    private val radiusKm = MutableStateFlow(StayCanyonPickerUiState.DEFAULT_RADIUS_KM)
    private val filter = MutableStateFlow(StayFilter.ALL)
    private val query = MutableStateFlow("")
    private val storedDrives = MutableStateFlow<Map<Int, DriveTime>>(emptyMap())

    private val stay = stayRepository.observeAll().map { stays -> stays.firstOrNull { it.id == stayId } }.distinctUntilChanged()
    private val place = combine(stay, placeRepository.observeAll()) { stay, places -> places.firstOrNull { it.id == stay?.placeId } }
        .distinctUntilChanged()

    /** The other days of the stay each canyon is planned for. */
    private val planned = combine(stay, outingRepository.observeAll()) { stay, outings ->
        stay ?: return@combine emptyMap()
        outings.filter(stay::includes)
            .flatMap { outing -> outing.canyonIds.map { it to outing.dayIn(clock.zone) } }
            .filter { (_, day) -> day != date }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, days) -> days.min() }
    }.distinctUntilChanged()

    private val nearby = combine(
        combine(place, radiusKm, filter, query) { place, radius, filter, query -> Criteria(place, radius, filter, query) },
        canyonRepository.observeSearchCatalog(),
        logbookRepository.observeDescendedCanyonIds(),
    ) { criteria, catalog, descended ->
        val place = criteria.place ?: return@combine emptyList<CanyonSearchItem>()
        val text = criteria.query.normalizeForSearch().takeIf { it.length >= StayCanyonPickerUiState.MIN_QUERY_LENGTH }
        catalog.asSequence()
            .filter { !it.isForbidden }
            .filter { text == null || it.normalizedNom.contains(text) || it.normalizedNomComplet.contains(text) }
            .mapNotNull { item ->
                val latitude = item.representativeLat ?: return@mapNotNull null
                val longitude = item.representativeLng ?: return@mapNotNull null
                val km = haversineKm(place.latitude, place.longitude, latitude, longitude)
                if (text != null || km <= criteria.radiusKm) item.copy(isDescended = item.id in descended) to km else null
            }
            .filter { (item, _) ->
                when (criteria.filter) {
                    StayFilter.ALL -> true
                    StayFilter.FAVORITES -> item.isFavorite
                    StayFilter.NOT_DESCENDED -> !item.isDescended
                }
            }
            .sortedBy { it.second }
            .take(MAX_CANDIDATES)
            .map { it.first }
            .toList()
    }

    private val candidates = combine(place, nearby, storedDrives) { place, nearby, stored ->
        place ?: return@combine emptyList()
        nearby.map { item ->
            StayCandidate(
                canyon = item,
                drive = stored[item.id]
                    ?: DriveTime.estimate(place.latitude, place.longitude, item.representativeLat!!, item.representativeLng!!),
            )
        }.sortedBy { it.drive.minutes }
    }

    private val reports = candidates.map { list -> list.map { it.canyon.id }.toSet() }.distinctUntilChanged()
        .flatMapLatest { ids -> if (ids.isEmpty()) flowOf(emptyMap()) else debitRepository.observeLatestKnownDebits(ids) }

    val uiState: StateFlow<StayCanyonPickerUiState> = combine(
        combine(stay, place, ::Pair),
        combine(candidates, reports, planned, ::Triple),
        radiusKm,
        filter,
        query,
    ) { (stay, place), (candidates, reports, planned), radius, filter, query ->
        StayCanyonPickerUiState(
            isLoading = false,
            stay = stay,
            place = place,
            date = date,
            today = LocalDate.now(clock),
            outingId = outingId,
            radiusKm = radius,
            filter = filter,
            query = query,
            candidates = candidates.map { it.copy(lastReport = reports[it.canyon.id], plannedOn = planned[it.canyon.id]) },
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        StayCanyonPickerUiState(date = date, today = LocalDate.now(clock), outingId = outingId),
    )

    init {
        viewModelScope.launch {
            place.filterNotNull().collect { place -> storedDrives.value = driveTimeRepository.storedFrom(place.id) }
        }
        viewModelScope.launch { routeNearest() }
    }

    fun selectRadius(km: Int) = radiusKm.update { km }

    /** A filter touched again goes back to every canyon. */
    fun toggleFilter(value: StayFilter) = filter.update { if (it == value) StayFilter.ALL else value }

    fun search(text: String) = query.update { text }

    /**
     * Asks the road the nearest canyons on the list, still estimated, to their parking as their plan
     * would (the end one when the sheet has a shuttle): one route a second at most, as the public
     * routing service asks, each canyon once, a few per visit.
     */
    private suspend fun routeNearest() {
        val asked = mutableSetOf<Int>()
        combine(place.filterNotNull(), candidates, ::Pair).conflate().collect { (place, candidates) ->
            val toAsk = candidates.take(ROUTED_CANDIDATES)
                .filter { it.drive.source == DriveTimeSource.ESTIMATE && it.canyon.id !in asked }
                .take(MAX_ROUTES_PER_VISIT - asked.size)
            for (candidate in toAsk) {
                if (!connectivityObserver.isCurrentlyOnline()) return@collect
                asked += candidate.canyon.id
                val detail = canyonRepository.getCanyonPreview(candidate.canyon.id).getOrNull() ?: continue
                val parking = detail.meetingParking(withShuttle = Shuttle.parse(detail.canyon.navette) != null) ?: continue
                driveTimeRepository.route(place, candidate.canyon.id, parking).getOrNull()?.let { time ->
                    storedDrives.update { it + (candidate.canyon.id to time) }
                }
                delay(ROUTE_INTERVAL_MS)
            }
        }
    }

    private data class Criteria(val place: Place?, val radiusKm: Int, val filter: StayFilter, val query: String)

    companion object {
        const val MAX_CANDIDATES = 30

        /** The first canyons of the list whose real drive is asked. */
        const val ROUTED_CANDIDATES = 8
        private const val MAX_ROUTES_PER_VISIT = 12
        private const val ROUTE_INTERVAL_MS = 1_100L
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StayCanyonPickerScreen(
    onBackClick: () -> Unit,
    onPick: (OutingPlanRequest) -> Unit,
    onOpenCanyon: (canyonId: Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StayCanyonPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)
    var radiusMenu by remember { mutableStateOf(false) }
    // The text stays here: fed back by the state, the field would lose the letters typed meanwhile.
    var query by rememberSaveable { mutableStateOf("") }
    Scaffold(
        modifier = modifier,
        topBar = {
            CompactAppBar(
                title = stringResource(if (state.outingId > 0) R.string.stay_outing_change else R.string.stay_choose_canyon),
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
                modifier = Modifier.fillMaxHeight().width(contentWidth).testTag(TestTags.STAY_PICKER_LIST),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val day = stayDayLabel(state.date)
                        Text(
                            state.place?.let { stringResource(R.string.stay_picker_subtitle, day, it.name) } ?: day,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedTextField(
                            value = query,
                            onValueChange = {
                                query = it
                                viewModel.search(it)
                            },
                            placeholder = { Text(stringResource(R.string.stay_picker_search_hint)) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag(TestTags.STAY_PICKER_SEARCH),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // A name searches everywhere: the radius would not apply.
                            if (!state.isSearching) {
                                Box {
                                    FilterChip(
                                        selected = true,
                                        onClick = { radiusMenu = true },
                                        label = { Text(stringResource(R.string.stay_radius, state.radiusKm)) },
                                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                                    )
                                    DcDropdownMenu(expanded = radiusMenu, onDismissRequest = { radiusMenu = false }) {
                                        StayCanyonPickerUiState.RADII_KM.forEach { km ->
                                            DcDropdownMenuItem(
                                                text = stringResource(R.string.stay_radius, km),
                                                isSelected = km == state.radiusKm,
                                                onClick = {
                                                    radiusMenu = false
                                                    viewModel.selectRadius(km)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            FilterChip(
                                selected = state.filter == StayFilter.FAVORITES,
                                onClick = { viewModel.toggleFilter(StayFilter.FAVORITES) },
                                label = { Text(stringResource(R.string.stay_filter_favorites)) },
                            )
                            FilterChip(
                                selected = state.filter == StayFilter.NOT_DESCENDED,
                                onClick = { viewModel.toggleFilter(StayFilter.NOT_DESCENDED) },
                                label = { Text(stringResource(R.string.stay_filter_not_descended)) },
                            )
                        }
                    }
                }
                if (!state.isLoading && state.candidates.isEmpty()) {
                    item(key = "empty") {
                        DcEmptyState(
                            title = stringResource(if (state.isSearching) R.string.stay_search_empty else R.string.stay_empty),
                            body = stringResource(if (state.isSearching) R.string.stay_search_empty_hint else R.string.stay_empty_hint),
                        )
                    }
                }
                items(state.candidates, key = { it.canyon.id }) { candidate ->
                    CandidateCard(
                        candidate = candidate,
                        onPick = {
                            val stay = state.stay ?: return@CandidateCard
                            onPick(
                                if (state.outingId > 0) {
                                    // The outing of the day takes this canyon, its day and group kept.
                                    OutingPlanRequest(candidate.canyon.id, state.outingId)
                                } else {
                                    OutingPlanRequest(candidate.canyon.id, date = state.date, placeId = stay.placeId, stayId = stay.id)
                                },
                            )
                        },
                        onOpenSheet = { onOpenCanyon(candidate.canyon.id) },
                    )
                }
            }
        }
    }
}

/** The card of the search and the favorites, with the drive from the place and the other day it is planned for. */
@Composable
private fun CandidateCard(candidate: StayCandidate, onPick: () -> Unit, onOpenSheet: () -> Unit) {
    val colors = LocalDcColors.current
    val canyon = candidate.canyon
    CanyonSummaryCard(
        canyon = canyon.toSummary().copy(dernierDebit = candidate.lastReport?.niveau, dernierDebitDate = candidate.lastReport?.date),
        onClick = onPick,
        isDescended = canyon.isDescended,
        footer = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.Default.DirectionsCar,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    stringResource(
                        if (candidate.drive.source == DriveTimeSource.ESTIMATE) R.string.stay_drive_estimate else R.string.stay_drive,
                        formatMinutes(candidate.drive.minutes.toLong()),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onOpenSheet,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.testTag(TestTags.stayCandidateSheet(canyon.id)),
                ) { Text(stringResource(R.string.stay_picker_open_sheet)) }
            }
            candidate.plannedOn?.let { day ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.Default.EventAvailable,
                        contentDescription = null,
                        tint = colors.warning,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        stringResource(R.string.stay_picker_planned, shortDayLabel(day)),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
        },
    )
}
