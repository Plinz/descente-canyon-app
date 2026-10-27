package fr.descentecanyon.app.ui.outing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.Stay
import fr.descentecanyon.app.domain.model.agenda
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.PlaceRepository
import fr.descentecanyon.app.domain.repository.StayRepository
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcCardVariant
import fr.descentecanyon.app.ui.design.DcEmptyState
import fr.descentecanyon.app.ui.design.DcOutdoorActionCard
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.test.TestTags
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Opens the plan of an outing: a kept one ([outingId]), or a new one of [date] from [placeId] (0: none),
 * for a day of the stay [stayId] (0: none); [startNow] turns its follow-up on at once.
 */
data class OutingPlanRequest(
    val canyonId: Int,
    val outingId: Long = 0,
    val date: java.time.LocalDate? = null,
    val placeId: Long = 0,
    val stayId: Long = 0,
    val startNow: Boolean = false,
)

/** A stay with its place, its outings planned and the canyons done during it. */
data class StaySummary(val stay: Stay, val place: Place?, val outings: Int, val done: Int = 0)

data class OutingsUiState(
    val isLoading: Boolean = true,
    val underway: PlannedOuting? = null,
    val upcoming: List<PlannedOuting> = emptyList(),
    /** Drafts outside the stays shown: those are in their stay. */
    val drafts: List<PlannedOuting> = emptyList(),
    /** The stays still to come or under way. */
    val stays: List<StaySummary> = emptyList(),
    /** The stays over, last one first. */
    val pastStays: List<StaySummary> = emptyList(),
) {
    val isEmpty: Boolean get() = underway == null && upcoming.isEmpty() && drafts.isEmpty() && stays.isEmpty()
}

/** The outing under way first, the followed ones, the stays, the drafts, then the stays over. */
@HiltViewModel
class OutingsViewModel @Inject constructor(
    outingRepository: OutingRepository,
    stayRepository: StayRepository,
    placeRepository: PlaceRepository,
    logbookRepository: LogbookRepository,
    clock: Clock,
) : ViewModel() {

    /** Outings move from planned to under way with the time. */
    val uiState: StateFlow<OutingsUiState> = combine(
        outingRepository.observeAll().agenda(clock),
        stayRepository.observeAll(),
        placeRepository.observeAll(),
        logbookRepository.observeAll(),
    ) { agenda, stays, places, logbook ->
        val today = LocalDate.now(clock)
        val (over, shown) = stays.partition { it.isOver(today) }
        val all = listOfNotNull(agenda.underway) + agenda.upcoming + agenda.drafts
        fun summary(stay: Stay) = StaySummary(
            stay = stay,
            place = places.firstOrNull { it.id == stay.placeId },
            outings = all.count(stay::includes),
            done = logbook.count { stay.covers(it.date) },
        )
        OutingsUiState(
            isLoading = false,
            underway = agenda.underway,
            upcoming = agenda.upcoming,
            drafts = agenda.drafts.filterNot { draft -> shown.any { it.includes(draft) } },
            stays = shown.map(::summary),
            pastStays = over.sortedByDescending { it.end }.map(::summary),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), OutingsUiState())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun OutingsScreen(
    onBackClick: () -> Unit,
    onOpenOuting: (OutingPlanRequest) -> Unit,
    onOpenStay: (stayId: Long) -> Unit,
    onNewStay: () -> Unit,
    onManagePlaces: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OutingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)
    var showPast by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            CompactAppBar(
                title = stringResource(R.string.outings_title),
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
                modifier = Modifier.fillMaxHeight().width(contentWidth).testTag(TestTags.OUTINGS_LIST),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.underway?.let { outing ->
                    item(key = "underway") {
                        SectionTitle(stringResource(R.string.outings_underway))
                        OutingRow(outing = outing, underway = true, onClick = {
                            onOpenOuting(OutingPlanRequest(outing.canyonId, outing.id))
                        })
                    }
                }
                // Followed outings first: a relative may be waiting on them.
                if (state.upcoming.isNotEmpty()) {
                    item(key = "upcoming") { SectionTitle(stringResource(R.string.outings_upcoming)) }
                    items(state.upcoming, key = { "outing-${it.id}" }) { outing ->
                        OutingRow(outing = outing, underway = false, onClick = {
                            onOpenOuting(OutingPlanRequest(outing.canyonId, outing.id))
                        })
                    }
                }
                if (state.stays.isNotEmpty()) {
                    item(key = "stays") { SectionTitle(stringResource(R.string.outings_stays)) }
                    items(state.stays, key = { "stay-${it.stay.id}" }) { summary ->
                        StayRow(summary = summary, onClick = { onOpenStay(summary.stay.id) })
                    }
                    item(key = "new-stay") {
                        TextButton(onClick = onNewStay, modifier = Modifier.testTag(TestTags.OUTINGS_NEW_STAY)) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Text(stringResource(R.string.stay_new), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                if (state.drafts.isNotEmpty()) {
                    item(key = "drafts") { SectionTitle(stringResource(R.string.outings_drafts)) }
                    items(state.drafts, key = { "draft-${it.id}" }) { outing ->
                        OutingRow(outing = outing, underway = false, onClick = {
                            onOpenOuting(OutingPlanRequest(outing.canyonId, outing.id))
                        })
                    }
                }
                if (!state.isLoading && state.isEmpty) {
                    item(key = "empty") {
                        DcEmptyState(
                            title = stringResource(R.string.outings_empty_title),
                            body = stringResource(R.string.outings_empty_body),
                            icon = Icons.Default.EventAvailable,
                        )
                    }
                }
                // What a stay is, until the first one: then a link in its section is enough.
                if (!state.isLoading && state.stays.isEmpty()) {
                    item(key = "new-stay") {
                        DcOutdoorActionCard(
                            title = stringResource(R.string.outings_stay_action),
                            hint = stringResource(R.string.outings_stay_action_hint),
                            icon = Icons.Default.CalendarMonth,
                            onClick = onNewStay,
                            modifier = Modifier.fillMaxWidth().testTag(TestTags.OUTINGS_NEW_STAY),
                        )
                    }
                }
                if (state.pastStays.isNotEmpty()) {
                    item(key = "past-stays") {
                        TextButton(
                            onClick = { showPast = !showPast },
                            modifier = Modifier.testTag(TestTags.OUTINGS_PAST_STAYS),
                        ) {
                            Text(stringResource(R.string.outings_past_stays, state.pastStays.size))
                            Icon(if (showPast) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                        }
                    }
                    if (showPast) {
                        items(state.pastStays, key = { "past-stay-${it.stay.id}" }) { summary ->
                            StayRow(summary = summary, onClick = { onOpenStay(summary.stay.id) })
                        }
                    }
                }
                item(key = "places") {
                    OutlinedButton(onClick = onManagePlaces, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Place, contentDescription = null)
                        Text(stringResource(R.string.places_title), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StayRow(summary: StaySummary, onClick: () -> Unit) {
    val colors = LocalDcColors.current
    DcCard(modifier = Modifier.fillMaxWidth().testTag(TestTags.stayRow(summary.stay.id)), onClick = onClick, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    summary.place?.name ?: stringResource(R.string.stay_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(stayRangeLabel(summary.stay), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                Text(
                    listOfNotNull(
                        pluralStringResource(R.plurals.stay_outings_count, summary.outings, summary.outings).takeIf { summary.outings > 0 },
                        pluralStringResource(R.plurals.stay_done_count, summary.done, summary.done).takeIf { summary.done > 0 },
                    ).ifEmpty { listOf(pluralStringResource(R.plurals.stay_outings_count, 0, 0)) }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textMuted)
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/** A planned outing: its day, when it starts and its estimated return, and when the relative worries. */
@Composable
internal fun OutingRow(outing: PlannedOuting, underway: Boolean, onClick: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val zone = ZoneId.systemDefault()
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    val day = outing.start.atZone(zone).toLocalDate()
    val dayLabel = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(day).replaceFirstChar {
        it.titlecase(locale)
    }
    val departure = (outing.departure ?: outing.start).atZone(zone).toLocalTime().format(time)
    val back = outing.expectedBack.atZone(zone).toLocalTime().format(time)
    val alert = timeOfOuting(outing.alertAt.atZone(zone).toLocalDateTime(), day, locale)
    DcCard(
        modifier = Modifier.fillMaxWidth().testTag(TestTags.outingRow(outing.id)),
        variant = if (underway) DcCardVariant.Condition else DcCardVariant.Surface,
        onClick = onClick,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Hiking, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(outing.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(dayLabel, style = MaterialTheme.typography.bodySmall)
                val times = if (outing.departure != null) R.string.outings_row_times else R.string.outings_row_times_walk
                Text(stringResource(times, departure, back), style = MaterialTheme.typography.bodySmall)
                Text(
                    buildString {
                        append(
                            if (outing.tracked) {
                                stringResource(
                                    R.string.outings_row_alert,
                                    alert,
                                )
                            } else {
                                stringResource(R.string.outings_row_draft)
                            },
                        )
                        outing.groupSize?.let { append(" · ").append(pluralStringResource(R.plurals.outing_group_size, it, it)) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalDcColors.current.textSecondary,
                )
            }
        }
    }
}
