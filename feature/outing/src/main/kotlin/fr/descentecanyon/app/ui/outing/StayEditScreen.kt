package fr.descentecanyon.app.ui.outing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.model.Stay
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.PlaceRepository
import fr.descentecanyon.app.domain.repository.PreferencesRepository
import fr.descentecanyon.app.domain.repository.StayRepository
import fr.descentecanyon.app.domain.usecase.SaveStayUseCase
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcDateRangePickerDialog
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.map.MapCameraState
import fr.descentecanyon.app.ui.test.TestTags
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StayEditUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    val places: List<Place> = emptyList(),
    val placeId: Long? = null,
    val start: LocalDate,
    val end: LocalDate,
    /** The first day the calendar offers: today, or the first day of a stay already begun. */
    val minDate: LocalDate,
    /** The outings of the stay its new dates leave out: they leave it when it is kept. */
    val outingsLeftOut: Int = 0,
    /** Set once kept: the screen leaves for the stay. */
    val savedId: Long? = null,
) {
    val canSave: Boolean get() = placeId != null && places.any { it.id == placeId } && !end.isBefore(start)
}

/** A new stay, or one changed: its place (a campsite placed on the map) and its days. */
@HiltViewModel
class StayEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val stayRepository: StayRepository,
    private val placeRepository: PlaceRepository,
    outingRepository: OutingRepository,
    private val saveStayUseCase: SaveStayUseCase,
    preferencesRepository: PreferencesRepository,
    clock: Clock,
) : ViewModel() {

    private val stayId: Long = savedStateHandle["stayId"] ?: 0L
    private val today = LocalDate.now(clock)
    private val form = MutableStateFlow<Form?>(null)
    private val kept = MutableStateFlow<Stay?>(null)
    private val savedId = MutableStateFlow<Long?>(null)

    /** The last area seen on the map, for a new place. */
    val initialCamera: MapCameraState = PlacesViewModel.newPlaceCamera(preferencesRepository)

    val uiState: StateFlow<StayEditUiState> = combine(
        placeRepository.observeAll(),
        form,
        kept,
        outingRepository.observeAll(),
        savedId,
    ) { places, form, kept, outings, saved ->
        val leftOut = if (kept != null && form != null) {
            saveStayUseCase.outingsLeftOut(kept.copy(start = form.start, end = form.end), outings).size
        } else {
            0
        }
        StayEditUiState(
            isLoading = form == null,
            isNew = stayId == 0L,
            places = places,
            placeId = form?.placeId,
            start = form?.start ?: today,
            end = form?.end ?: today,
            minDate = kept?.start?.let { minOf(it, today) } ?: today,
            outingsLeftOut = leftOut,
            savedId = saved,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        StayEditUiState(start = today, end = today, minDate = today),
    )

    init {
        viewModelScope.launch {
            val stay = stayId.takeIf { it > 0 }?.let { stayRepository.get(it) }
            val places = placeRepository.observeAll().first()
            kept.value = stay
            form.value = Form(
                placeId = stay?.placeId ?: places.firstOrNull()?.id,
                start = stay?.start ?: today,
                end = stay?.end ?: today.plusDays(Stay.DEFAULT_DAYS - 1),
            )
        }
    }

    fun selectPlace(id: Long) = form.update { it?.copy(placeId = id) }

    /** The first and last days, the stay cut to [Stay.MAX_DAYS]. */
    fun selectDates(start: LocalDate, end: LocalDate) = form.update { form ->
        val first = minOf(start, end)
        form?.copy(start = first, end = minOf(maxOf(start, end), lastDay(first)))
    }

    /** A new place, placed in the editor, becomes the place of the stay. */
    fun addPlace(place: Place) {
        viewModelScope.launch {
            val id = placeRepository.save(place)
            form.update { it?.copy(placeId = id) }
        }
    }

    fun save() {
        val form = form.value ?: return
        val placeId = form.placeId ?: return
        viewModelScope.launch {
            savedId.value = saveStayUseCase(Stay(placeId, form.start, minOf(form.end, lastDay(form.start)), id = stayId))
        }
    }

    private data class Form(val placeId: Long?, val start: LocalDate, val end: LocalDate)

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** The last day a stay starting on [start] can have. */
        internal fun lastDay(start: LocalDate): LocalDate = start.plusDays(Stay.MAX_DAYS - 1L)
    }
}

@Composable
fun StayEditScreen(onClose: () -> Unit, onSaved: (stayId: Long, isNew: Boolean) -> Unit, viewModel: StayEditViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalDcColors.current
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalLocale.current.platformLocale)
    var addingPlace by rememberSaveable { mutableStateOf(false) }
    var pickingDates by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.savedId) { state.savedId?.let { onSaved(it, state.isNew) } }
    if (addingPlace) {
        PlaceEditor(
            initial = null,
            initialCamera = viewModel.initialCamera,
            onSave = { place ->
                addingPlace = false
                viewModel.addPlace(place)
            },
            onDismiss = { addingPlace = false },
        )
    }
    if (pickingDates) {
        DcDateRangePickerDialog(
            initialStart = state.start,
            initialEnd = state.end,
            onRangeSelected = viewModel::selectDates,
            onDismiss = { pickingDates = false },
            minDate = state.minDate,
        )
    }
    Scaffold(
        topBar = {
            CompactAppBar(
                title = stringResource(if (state.isNew) R.string.stay_new else R.string.stay_edit),
                navigation = {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel)) }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = state.canSave, modifier = Modifier.testTag(TestTags.STAY_EDIT_SAVE)) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        if (state.isLoading) return@Scaffold
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.stay_edit_intro), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            Text(stringResource(R.string.stay_place_label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Column {
                state.places.forEach { place ->
                    Box(Modifier.testTag(TestTags.stayPlaceOption(place.id))) {
                        PlaceOption(label = place.name, selected = place.id == state.placeId, onClick = { viewModel.selectPlace(place.id) })
                    }
                }
            }
            if (state.places.isEmpty()) {
                Text(stringResource(R.string.stay_no_place), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            OutlinedButton(onClick = { addingPlace = true }, modifier = Modifier.testTag(TestTags.STAY_ADD_PLACE)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(stringResource(R.string.places_add), modifier = Modifier.padding(start = 8.dp))
            }
            Text(stringResource(R.string.stay_dates_label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val days = (state.end.toEpochDay() - state.start.toEpochDay() + 1).toInt()
            DcCard(modifier = Modifier.fillMaxWidth().testTag(TestTags.STAY_DATES), onClick = { pickingDates = true }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.DateRange, contentDescription = null, tint = colors.textSecondary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.stay_dates_value, state.start.format(formatter), state.end.format(formatter)),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            pluralStringResource(R.plurals.stay_days_count, days, days),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.stay_dates_change), tint = colors.textMuted)
                }
            }
            Text(
                pluralStringResource(R.plurals.stay_dates_hint, Stay.MAX_DAYS, Stay.MAX_DAYS),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
            if (state.outingsLeftOut > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.testTag(TestTags.STAY_EDIT_LEFT_OUT),
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = colors.warning, modifier = Modifier.size(18.dp))
                    Text(
                        pluralStringResource(R.plurals.stay_edit_left_out, state.outingsLeftOut, state.outingsLeftOut),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
