package fr.descentecanyon.app.ui.outing

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
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
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.DailyWeather
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.DriveTime
import fr.descentecanyon.app.domain.model.DriveTimeSource
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.Stay
import fr.descentecanyon.app.domain.model.WeatherCondition
import fr.descentecanyon.app.domain.model.haversineKm
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.DriveTimeRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.PlaceRepository
import fr.descentecanyon.app.domain.repository.PreferencesRepository
import fr.descentecanyon.app.domain.repository.StayRepository
import fr.descentecanyon.app.domain.usecase.GetPlaceWeatherUseCase
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.components.debitLevelColor
import fr.descentecanyon.app.ui.components.debitLevelLabel
import fr.descentecanyon.app.ui.components.relativeDayLabel
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcDatePickerDialog
import fr.descentecanyon.app.ui.design.DcDropdownMenu
import fr.descentecanyon.app.ui.design.DcDropdownMenuItem
import fr.descentecanyon.app.ui.design.DcEmptyState
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.map.MapCameraState
import fr.descentecanyon.app.ui.test.TestTags
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// A stay: where and when.
// ---------------------------------------------------------------------------------------------

data class StayEditUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    val places: List<Place> = emptyList(),
    val placeId: Long? = null,
    val start: LocalDate,
    val end: LocalDate,
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
    preferencesRepository: PreferencesRepository,
    clock: Clock,
) : ViewModel() {

    private val stayId: Long = savedStateHandle["stayId"] ?: 0L
    private val today = LocalDate.now(clock)
    private val form = MutableStateFlow<Form?>(null)
    private val savedId = MutableStateFlow<Long?>(null)

    /** The last area seen on the map, for a new place. */
    val initialCamera: MapCameraState = PlacesViewModel.newPlaceCamera(preferencesRepository)

    val uiState: StateFlow<StayEditUiState> = combine(placeRepository.observeAll(), form, savedId) { places, form, saved ->
        StayEditUiState(
            isLoading = form == null,
            isNew = stayId == 0L,
            places = places,
            placeId = form?.placeId,
            start = form?.start ?: today,
            end = form?.end ?: today,
            savedId = saved,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StayEditUiState(start = today, end = today))

    init {
        viewModelScope.launch {
            val stay = stayId.takeIf { it > 0 }?.let { stayRepository.get(it) }
            val places = placeRepository.observeAll().first()
            form.value = Form(
                placeId = stay?.placeId ?: places.firstOrNull()?.id,
                start = stay?.start ?: today,
                end = stay?.end ?: today.plusDays(Stay.DEFAULT_DAYS - 1),
            )
        }
    }

    fun selectPlace(id: Long) = form.update { it?.copy(placeId = id) }

    /** The first day: the stay moves with it and keeps its length. */
    fun selectStart(date: LocalDate) = form.update { form ->
        form?.let { form.copy(start = date, end = minOf(date.plusDays(ChronoUnit.DAYS.between(it.start, it.end)), lastDay(date))) }
    }

    fun selectEnd(date: LocalDate) = form.update { form ->
        form?.copy(end = date, start = if (date.isBefore(form.start)) date else form.start)
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
            savedId.value = stayRepository.save(Stay(placeId, form.start, minOf(form.end, lastDay(form.start)), id = stayId))
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
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
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
    when (picking) {
        START -> DcDatePickerDialog(initialDate = state.start, onDateSelected = viewModel::selectStart, onDismiss = { picking = null })

        END -> DcDatePickerDialog(
            initialDate = state.end,
            onDateSelected = viewModel::selectEnd,
            onDismiss = { picking = null },
            maxDate = StayEditViewModel.lastDay(state.start),
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picking = START }) { Text(state.start.format(formatter)) }
                Text("→")
                OutlinedButton(onClick = { picking = END }) { Text(state.end.format(formatter)) }
            }
            val days = (state.end.toEpochDay() - state.start.toEpochDay() + 1).toInt()
            Text(
                pluralStringResource(R.plurals.stay_days_count, days, days),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

private const val START = "start"
private const val END = "end"

// ---------------------------------------------------------------------------------------------
// The program of a stay, day by day.
// ---------------------------------------------------------------------------------------------

/** A day of the stay: the weather at the place and the outings planned. */
data class StayDay(val date: LocalDate, val weather: DailyWeather? = null, val outings: List<PlannedOuting> = emptyList())

data class StayUiState(
    val isLoading: Boolean = true,
    val stay: Stay? = null,
    val place: Place? = null,
    val today: LocalDate,
    val days: List<StayDay> = emptyList(),
    val isLoadingWeather: Boolean = false,
    /** The stay is gone (deleted here or with its place): the screen closes. */
    val isGone: Boolean = false,
)

/**
 * The days of a stay still to come, each with the weather at the place (the canyons around share it)
 * and its outings; a day without one offers to choose a canyon.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StayViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val stayRepository: StayRepository,
    placeRepository: PlaceRepository,
    outingRepository: OutingRepository,
    private val getPlaceWeatherUseCase: GetPlaceWeatherUseCase,
    private val connectivityObserver: ConnectivityObserver,
    private val clock: Clock,
) : ViewModel() {

    private val stayId: Long = savedStateHandle["stayId"] ?: 0L
    private val weather = MutableStateFlow<CanyonWeather?>(null)
    private val loadingWeather = MutableStateFlow(false)

    private val stay = stayRepository.observeAll().map { stays -> stays.firstOrNull { it.id == stayId } }.distinctUntilChanged()
    private val place = combine(stay, placeRepository.observeAll()) { stay, places -> places.firstOrNull { it.id == stay?.placeId } }
        .distinctUntilChanged()

    val uiState: StateFlow<StayUiState> = combine(stay, place, outingRepository.observeAll(), weather, loadingWeather) {
            stay,
            place,
            outings,
            weather,
            isLoadingWeather,
        ->
        val today = LocalDate.now(clock)
        if (stay == null) return@combine StayUiState(isLoading = false, today = today, isGone = true)
        val forecast = weather?.daily?.associateBy { it.date }.orEmpty()
        val ofStay = outings.filter(stay::includes)
        StayUiState(
            isLoading = false,
            stay = stay,
            place = place,
            today = today,
            days = stay.daysFrom(today).map { day ->
                StayDay(day, forecast[day], ofStay.filter { it.dayIn(clock.zone) == day })
            },
            isLoadingWeather = isLoadingWeather,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StayUiState(today = LocalDate.now(clock)))

    init {
        viewModelScope.launch {
            place.filterNotNull().map { it.copy(lastUsedAt = 0) }.distinctUntilChanged().collect(::loadWeather)
        }
    }

    fun delete() {
        viewModelScope.launch { stayRepository.delete(stayId) }
    }

    /** The forecast kept on the device first, then downloaded. */
    private suspend fun loadWeather(place: Place) {
        weather.value = getPlaceWeatherUseCase.cached(place)
        if (!connectivityObserver.isCurrentlyOnline()) return
        loadingWeather.value = true
        try {
            getPlaceWeatherUseCase(place).getOrNull()?.let { weather.value = it }
        } finally {
            loadingWeather.value = false
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** The day of an outing: the one chosen, else the day of its walk. */
private fun PlannedOuting.dayIn(zone: ZoneId): LocalDate = choices?.date ?: start.atZone(zone).toLocalDate()

@Composable
fun StayScreen(
    onBackClick: () -> Unit,
    onEdit: (stayId: Long) -> Unit,
    onChooseCanyon: (stayId: Long, date: LocalDate) -> Unit,
    onOpenOuting: (OutingPlanRequest) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.isGone) { if (state.isGone) onBackClick() }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.stay_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text(stringResource(R.string.stay_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            CompactAppBar(
                title = state.place?.name ?: stringResource(R.string.stay_title),
                navigation = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state.isLoadingWeather) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_actions))
                        }
                        DcDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DcDropdownMenuItem(
                                text = stringResource(R.string.stay_edit),
                                icon = Icons.Default.Edit,
                                onClick = {
                                    menu = false
                                    state.stay?.let { onEdit(it.id) }
                                },
                            )
                            DcDropdownMenuItem(
                                text = stringResource(R.string.stay_delete),
                                icon = Icons.Default.Delete,
                                onClick = {
                                    menu = false
                                    confirmDelete = true
                                },
                            )
                        }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        val stay = state.stay ?: return@Scaffold
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.fillMaxHeight().width(contentWidth).testTag(TestTags.STAY_LIST),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stayRangeLabel(stay), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.stay_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalDcColors.current.textSecondary,
                        )
                    }
                }
                if (state.days.isEmpty()) {
                    item(key = "over") { DcEmptyState(title = stringResource(R.string.stay_over), icon = Icons.Default.EventAvailable) }
                }
                items(state.days, key = { it.date.toString() }) { day ->
                    StayDayCard(
                        day = day,
                        today = state.today,
                        onChoose = { onChooseCanyon(stay.id, day.date) },
                        onOpen = { outing -> onOpenOuting(OutingPlanRequest(outing.canyonId, outing.id)) },
                    )
                }
            }
        }
    }
}

/** "Séjour du 12 juil. 2026 au 18 juil. 2026". */
@Composable
internal fun stayRangeLabel(stay: Stay): String {
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalLocale.current.platformLocale)
    return stringResource(R.string.places_stay_range, stay.start.format(formatter), stay.end.format(formatter))
}

@Composable
private fun StayDayCard(day: StayDay, today: LocalDate, onChoose: () -> Unit, onOpen: (PlannedOuting) -> Unit) {
    val colors = LocalDcColors.current
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    val title = DateTimeFormatter.ofPattern("EEEE d MMMM", locale).format(day.date).replaceFirstChar { it.titlecase(locale) }
    DcCard(modifier = Modifier.fillMaxWidth().testTag(TestTags.stayDay(day.date.toString())), contentPadding = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (day.date == today) {
                        Text(
                            stringResource(R.string.outing_day_today),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary,
                        )
                    }
                }
                day.weather?.let { weather ->
                    val storm = weather.condition == WeatherCondition.THUNDERSTORM
                    Icon(
                        weatherIcon(weather.condition),
                        contentDescription = stringResource(weatherConditionRes(weather.condition)),
                        tint = if (storm) MaterialTheme.colorScheme.error else colors.textSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(shortForecast(weather), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                }
            }
            if (day.weather?.condition == WeatherCondition.THUNDERSTORM) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        stringResource(R.string.outing_conditions_storm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else if (day.weather != null) {
                Text(forecastText(resources, day.weather, locale), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            day.outings.forEach { outing -> StayOutingRow(outing = outing, onClick = { onOpen(outing) }) }
            if (day.outings.isEmpty()) {
                TextButton(onClick = onChoose, modifier = Modifier.testTag(TestTags.stayChoose(day.date.toString()))) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(stringResource(R.string.stay_choose_canyon), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** "26 mm · 15°". */
@Composable
private fun shortForecast(weather: DailyWeather): String = listOfNotNull(
    weather.precipitationSumMm?.let { "${it.roundToInt()} mm" },
    weather.temperatureMaxC?.let { "${it.roundToInt()}°" },
).joinToString(" · ")

@Composable
private fun StayOutingRow(outing: PlannedOuting, onClick: () -> Unit) {
    val colors = LocalDcColors.current
    val locale = LocalLocale.current.platformLocale
    val zone = ZoneId.systemDefault()
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    val departure = (outing.departure ?: outing.start).atZone(zone).toLocalTime().format(time)
    val back = outing.expectedBack.atZone(zone).toLocalTime().format(time)
    DcCard(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(TestTags.outingRow(outing.id)), contentPadding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Hiking, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(outing.canyonName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
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
                    stringResource(if (outing.tracked) R.string.stay_outing_tracked else R.string.stay_outing_draft),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (outing.tracked) MaterialTheme.colorScheme.primary else colors.textMuted,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// A canyon for a day of the stay.
// ---------------------------------------------------------------------------------------------

/** Which canyons around the place are offered. */
enum class StayFilter {
    ALL,
    FAVORITES,
    NOT_DESCENDED,
}

/** A canyon that could fill the day. */
data class StayCandidate(val canyon: CanyonSearchItem, val drive: DriveTime, val lastReport: Debit? = null)

data class StayCanyonPickerUiState(
    val isLoading: Boolean = true,
    val stay: Stay? = null,
    val place: Place? = null,
    val date: LocalDate,
    val today: LocalDate,
    val radiusKm: Int = DEFAULT_RADIUS_KM,
    val filter: StayFilter = StayFilter.ALL,
    val candidates: List<StayCandidate> = emptyList(),
) {
    companion object {
        const val DEFAULT_RADIUS_KM = 40
        val RADII_KM = listOf(20, 40, 60)
    }
}

/** The canyons around the place of the stay, nearest by road first, for one of its days. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StayCanyonPickerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    stayRepository: StayRepository,
    placeRepository: PlaceRepository,
    canyonRepository: CanyonRepository,
    logbookRepository: LogbookRepository,
    private val driveTimeRepository: DriveTimeRepository,
    private val debitRepository: DebitRepository,
    private val clock: Clock,
) : ViewModel() {

    private val stayId: Long = savedStateHandle["stayId"] ?: 0L
    private val date: LocalDate = savedStateHandle.get<String>("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: LocalDate.now(clock)
    private val radiusKm = MutableStateFlow(StayCanyonPickerUiState.DEFAULT_RADIUS_KM)
    private val filter = MutableStateFlow(StayFilter.ALL)
    private val storedDrives = MutableStateFlow<Map<Int, DriveTime>>(emptyMap())

    private val stay = stayRepository.observeAll().map { stays -> stays.firstOrNull { it.id == stayId } }.distinctUntilChanged()
    private val place = combine(stay, placeRepository.observeAll()) { stay, places -> places.firstOrNull { it.id == stay?.placeId } }
        .distinctUntilChanged()

    private val nearby =
        combine(place, canyonRepository.observeSearchCatalog(), logbookRepository.observeDescendedCanyonIds(), radiusKm, filter) {
                place,
                catalog,
                descended,
                radius,
                filter,
            ->
            place ?: return@combine emptyList<CanyonSearchItem>()
            catalog.asSequence()
                .filter { !it.isForbidden }
                .mapNotNull { item ->
                    val latitude = item.representativeLat ?: return@mapNotNull null
                    val longitude = item.representativeLng ?: return@mapNotNull null
                    val km = haversineKm(place.latitude, place.longitude, latitude, longitude)
                    if (km <= radius) item.copy(isDescended = item.id in descended) to km else null
                }
                .filter { (item, _) ->
                    when (filter) {
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
        candidates,
        reports,
        radiusKm,
        filter,
    ) { (stay, place), candidates, reports, radius, filter ->
        StayCanyonPickerUiState(
            isLoading = false,
            stay = stay,
            place = place,
            date = date,
            today = LocalDate.now(clock),
            radiusKm = radius,
            filter = filter,
            candidates = candidates.map { it.copy(lastReport = reports[it.canyon.id]) },
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        StayCanyonPickerUiState(date = date, today = LocalDate.now(clock)),
    )

    init {
        viewModelScope.launch {
            place.filterNotNull().collect { place -> storedDrives.value = driveTimeRepository.storedFrom(place.id) }
        }
    }

    fun selectRadius(km: Int) = radiusKm.update { km }

    /** A filter touched again goes back to every canyon. */
    fun toggleFilter(value: StayFilter) = filter.update { if (it == value) StayFilter.ALL else value }

    companion object {
        const val MAX_CANDIDATES = 30
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StayCanyonPickerScreen(
    onBackClick: () -> Unit,
    onPick: (OutingPlanRequest) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StayCanyonPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)
    val locale = LocalLocale.current.platformLocale
    var radiusMenu by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            CompactAppBar(
                title = stringResource(R.string.stay_choose_canyon),
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
                        val day = DateTimeFormatter.ofPattern("EEEE d MMMM", locale).format(state.date).replaceFirstChar {
                            it.titlecase(locale)
                        }
                        Text(
                            state.place?.let { stringResource(R.string.stay_picker_subtitle, day, it.name) } ?: day,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        DcEmptyState(title = stringResource(R.string.stay_empty), body = stringResource(R.string.stay_empty_hint))
                    }
                }
                items(state.candidates, key = { it.canyon.id }) { candidate ->
                    CandidateRow(
                        candidate = candidate,
                        today = state.today,
                        onClick = {
                            val stay = state.stay ?: return@CandidateRow
                            onPick(OutingPlanRequest(candidate.canyon.id, date = state.date, placeId = stay.placeId, stayId = stay.id))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: StayCandidate, today: LocalDate, onClick: () -> Unit) {
    val colors = LocalDcColors.current
    val canyon = candidate.canyon
    DcCard(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(TestTags.stayCandidate(canyon.id)), contentPadding = 12.dp) {
        Column(modifier = Modifier.heightIn(min = 40.dp)) {
            Text(canyon.nom, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                buildList {
                    add(canyon.cotation)
                    add(
                        if (candidate.drive.source == DriveTimeSource.ESTIMATE) {
                            stringResource(R.string.stay_drive_estimate, formatMinutes(candidate.drive.minutes.toLong()))
                        } else {
                            stringResource(R.string.stay_drive, formatMinutes(candidate.drive.minutes.toLong()))
                        },
                    )
                    if (canyon.isDescended) add(stringResource(R.string.stay_descended))
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            candidate.lastReport?.let { report ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).background(debitLevelColor(report.niveau), MaterialTheme.shapes.small))
                    Text(
                        stringResource(
                            R.string.outing_conditions_last_report,
                            debitLevelLabel(report.niveau),
                            relativeDayLabel(report.date, today),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}
