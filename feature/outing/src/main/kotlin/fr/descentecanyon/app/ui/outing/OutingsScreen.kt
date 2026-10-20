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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EventAvailable
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
import fr.descentecanyon.app.domain.model.agenda
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.PlaceRepository
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcCardVariant
import fr.descentecanyon.app.ui.design.DcEmptyState
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.test.TestTags
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Opens the plan of an outing: a saved one ([outingId]), or a new one of [date] from [placeId] (0: none). */
data class OutingPlanRequest(val canyonId: Int, val outingId: Long = 0, val date: java.time.LocalDate? = null, val placeId: Long = 0)

data class OutingsUiState(
    val isLoading: Boolean = true,
    val underway: PlannedOuting? = null,
    val upcoming: List<PlannedOuting> = emptyList(),
    val places: List<Place> = emptyList(),
)

/** The outings planned, the one under way first, and the places they start from. */
@HiltViewModel
class OutingsViewModel @Inject constructor(outingRepository: OutingRepository, placeRepository: PlaceRepository, clock: Clock) :
    ViewModel() {

    /** Outings move from planned to under way with the time. */
    val uiState: StateFlow<OutingsUiState> = combine(outingRepository.observeAll().agenda(clock), placeRepository.observeAll()) {
            agenda,
            places,
        ->
        OutingsUiState(isLoading = false, underway = agenda.underway, upcoming = agenda.upcoming, places = places)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), OutingsUiState())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun OutingsScreen(
    onBackClick: () -> Unit,
    onOpenOuting: (OutingPlanRequest) -> Unit,
    onPlanStay: (placeId: Long) -> Unit,
    onManagePlaces: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OutingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)
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
                if (state.upcoming.isNotEmpty()) {
                    item(key = "upcoming") { SectionTitle(stringResource(R.string.outings_upcoming)) }
                    items(state.upcoming, key = { "outing-${it.id}" }) { outing ->
                        OutingRow(outing = outing, underway = false, onClick = {
                            onOpenOuting(OutingPlanRequest(outing.canyonId, outing.id))
                        })
                    }
                }
                if (!state.isLoading && state.underway == null && state.upcoming.isEmpty()) {
                    item(key = "empty") {
                        DcEmptyState(
                            title = stringResource(R.string.outings_empty_title),
                            body = stringResource(R.string.outings_empty_body),
                            icon = Icons.Default.EventAvailable,
                        )
                    }
                }
                item(key = "places") {
                    SectionTitle(stringResource(R.string.outings_places_title))
                    Text(
                        stringResource(R.string.outings_places_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalDcColors.current.textSecondary,
                    )
                }
                items(state.places, key = { "place-${it.id}" }) { place ->
                    PlaceStayRow(place = place, onPlanStay = { onPlanStay(place.id) })
                }
                item(key = "manage") {
                    OutlinedButton(onClick = onManagePlaces, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Place, contentDescription = null)
                        Text(
                            stringResource(if (state.places.isEmpty()) R.string.places_add else R.string.places_manage),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
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
                Text(outing.canyonName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(dayLabel, style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(
                        if (outing.departure !=
                            null
                        ) {
                            R.string.outings_row_times
                        } else {
                            R.string.outings_row_times_walk
                        },
                        departure,
                        back,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    buildString {
                        append(stringResource(R.string.outings_row_alert, alert))
                        outing.groupSize?.let { append(" · ").append(pluralStringResource(R.plurals.outing_group_size, it, it)) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalDcColors.current.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun PlaceStayRow(place: Place, onPlanStay: () -> Unit) {
    DcCard(modifier = Modifier.fillMaxWidth(), contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Place, contentDescription = null, tint = LocalDcColors.current.textSecondary)
            Column(modifier = Modifier.weight(1f)) {
                Text(place.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                stayLabel(place)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalDcColors.current.textSecondary) }
            }
            TextButton(onClick = onPlanStay, modifier = Modifier.testTag(TestTags.placePlan(place.id))) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null)
                Text(stringResource(R.string.stay_plan_action), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}
