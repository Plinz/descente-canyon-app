package fr.descentecanyon.app.ui.outing

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.DailyWeather
import fr.descentecanyon.app.domain.model.DaylightCheck
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.Stay
import fr.descentecanyon.app.domain.model.SunCalculator
import fr.descentecanyon.app.domain.model.WeatherCondition
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.PlaceRepository
import fr.descentecanyon.app.domain.repository.StayRepository
import fr.descentecanyon.app.domain.usecase.GetPlaceWeatherUseCase
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.components.debitLevelColor
import fr.descentecanyon.app.ui.components.debitLevelLabel
import fr.descentecanyon.app.ui.components.relativeDayLabel
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcDropdownMenu
import fr.descentecanyon.app.ui.design.DcDropdownMenuItem
import fr.descentecanyon.app.ui.design.DcEmptyState
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.test.TestTags
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A day of the stay: the weather at the place, the outings planned and the canyons done. */
data class StayDay(
    val date: LocalDate,
    val weather: DailyWeather? = null,
    val outings: List<PlannedOuting> = emptyList(),
    /** Back from these canyons on that day (the logbook). */
    val done: List<LogbookEntry> = emptyList(),
    /** Beyond the forecast yet: the day its forecast comes. */
    val forecastFrom: LocalDate? = null,
)

/** What to look at before keeping an outing of the stay. */
data class StayOutingSignals(
    /** At worst back at the car after the sunset, or too close to it. */
    val endsAtNight: Boolean = false,
    val lastReport: Debit? = null,
)

/** Why the days the forecast covers show no weather. */
enum class StayWeatherProblem {
    OFFLINE,
    FAILED,
}

data class StayUiState(
    val isLoading: Boolean = true,
    val stay: Stay? = null,
    val place: Place? = null,
    val today: LocalDate,
    val days: List<StayDay> = emptyList(),
    /** The canyons done on the days of the stay before today. */
    val done: List<LogbookEntry> = emptyList(),
    /** By outing id. */
    val signals: Map<Long, StayOutingSignals> = emptyMap(),
    val isLoadingWeather: Boolean = false,
    val weatherProblem: StayWeatherProblem? = null,
    /** The stay is gone (deleted here or with its place): the screen closes. */
    val isGone: Boolean = false,
)

/**
 * The days of a stay still to come, each with the weather at the place (the canyons around share it),
 * its outings and what to watch on them; a day without one offers to choose a canyon. The canyons
 * already done during the stay come from the logbook.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StayViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val stayRepository: StayRepository,
    placeRepository: PlaceRepository,
    private val outingRepository: OutingRepository,
    logbookRepository: LogbookRepository,
    canyonRepository: CanyonRepository,
    debitRepository: DebitRepository,
    private val getPlaceWeatherUseCase: GetPlaceWeatherUseCase,
    private val connectivityObserver: ConnectivityObserver,
    private val clock: Clock,
) : ViewModel() {

    private val stayId: Long = savedStateHandle["stayId"] ?: 0L
    private val weather = MutableStateFlow(WeatherState())

    private val stay = stayRepository.observeAll().map { stays -> stays.firstOrNull { it.id == stayId } }.distinctUntilChanged()
    private val place = combine(stay, placeRepository.observeAll()) { stay, places -> places.firstOrNull { it.id == stay?.placeId } }
        .distinctUntilChanged()
    private val outings = combine(stay, outingRepository.observeAll()) { stay, outings ->
        stay?.let { outings.filter(it::includes) }.orEmpty()
    }.distinctUntilChanged()
    private val canyonIds = outings.map { list -> list.map { it.canyonId }.toSet() }.distinctUntilChanged()

    /** Where each canyon of the stay starts, for its sunset: the point the plan computes it at. */
    private val positions = canyonIds.mapLatest { ids ->
        ids.mapNotNull { id ->
            val points = canyonRepository.getCanyonPreview(id).getOrNull()?.geoPoints.orEmpty()
            val point = points.firstOrNull { it.type == GeoPointType.PARKING_AMONT }
                ?: points.firstOrNull { it.type == GeoPointType.ENTREE }
                ?: points.firstOrNull()
                ?: return@mapNotNull null
            id to (point.latitude to point.longitude)
        }.toMap()
    }
    private val reports = canyonIds.flatMapLatest { ids ->
        if (ids.isEmpty()) flowOf(emptyMap()) else debitRepository.observeLatestKnownDebits(ids)
    }
    private val signals = combine(outings, positions, reports) { outings, positions, reports ->
        outings.associate { outing ->
            outing.id to
                StayOutingSignals(endsAtNight = endsAtNight(outing, positions[outing.canyonId]), lastReport = reports[outing.canyonId])
        }
    }

    val uiState: StateFlow<StayUiState> = combine(
        combine(stay, place, ::Pair),
        outings,
        logbookRepository.observeAll(),
        signals,
        weather,
    ) { (stay, place), outings, logbook, signals, weather ->
        val today = LocalDate.now(clock)
        if (stay == null) return@combine StayUiState(isLoading = false, today = today, isGone = true)
        val forecast = weather.forecast?.daily?.associateBy { it.date }.orEmpty()
        val lastForecastDay = today.plusDays(CanyonWeather.FORECAST_DAYS - 1L)
        val ofStay = logbook.filter { stay.covers(it.date) }
        val days = stay.daysFrom(today).map { day ->
            StayDay(
                date = day,
                weather = forecast[day],
                outings = outings.filter { it.dayIn(clock.zone) == day },
                done = ofStay.filter { it.date == day },
                forecastFrom = day.minusDays(CanyonWeather.FORECAST_DAYS - 1L).takeIf { day.isAfter(lastForecastDay) },
            )
        }
        StayUiState(
            isLoading = false,
            stay = stay,
            place = place,
            today = today,
            days = days,
            done = ofStay.filter { it.date.isBefore(today) }.sortedBy { it.date },
            signals = signals,
            isLoadingWeather = weather.isLoading,
            // Only while some days could have their forecast.
            weatherProblem = weather.problem?.takeIf { weather.forecast == null && days.any { it.forecastFrom == null } },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StayUiState(today = LocalDate.now(clock)))

    init {
        viewModelScope.launch {
            // A stay over has no day left to forecast.
            combine(stay, place) { stay, place -> place?.takeIf { stay != null && !stay.isOver(LocalDate.now(clock)) } }
                .filterNotNull()
                .map { it.copy(lastUsedAt = 0) }
                .distinctUntilChanged()
                .collect(::loadWeather)
        }
    }

    fun delete() {
        viewModelScope.launch { stayRepository.delete(stayId) }
    }

    /** Takes a draft off its day, which offers a canyon again; a followed outing is changed from its plan. */
    fun removeOuting(outing: PlannedOuting) {
        if (outing.tracked) return
        viewModelScope.launch { outingRepository.delete(outing.id) }
    }

    /** The forecast kept on the device first, then downloaded. */
    private suspend fun loadWeather(place: Place) {
        val cached = getPlaceWeatherUseCase.cached(place)
        if (!connectivityObserver.isCurrentlyOnline()) {
            weather.value = WeatherState(cached, problem = StayWeatherProblem.OFFLINE)
            return
        }
        weather.value = WeatherState(cached, isLoading = true)
        val downloaded = getPlaceWeatherUseCase(place).getOrNull()
        weather.value = WeatherState(downloaded ?: cached, problem = StayWeatherProblem.FAILED.takeIf { downloaded == null })
    }

    /** By the sunset at the canyon on the day of the outing, computed on the device. */
    private fun endsAtNight(outing: PlannedOuting, position: Pair<Double, Double>?): Boolean {
        val (latitude, longitude) = position ?: return false
        val day = outing.dayIn(clock.zone)
        val sunset = SunCalculator.sunTimes(day, latitude, longitude, clock.zone).sunset ?: return false
        return DaylightCheck.endsTooLate(day, outing.latestBack.atZone(clock.zone).toLocalDateTime(), sunset)
    }

    private data class WeatherState(
        val forecast: CanyonWeather? = null,
        val isLoading: Boolean = false,
        val problem: StayWeatherProblem? = null,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

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
                item(key = "header") { StayHeader(stay = stay, weatherProblem = state.weatherProblem) }
                if (state.days.isEmpty()) {
                    item(key = "over") {
                        DcEmptyState(
                            title = stringResource(R.string.stay_over),
                            body = state.done.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.stay_done_count, it, it) },
                            icon = Icons.Default.EventAvailable,
                        )
                    }
                }
                if (state.done.isNotEmpty()) {
                    item(key = "done") { StayDoneCard(done = state.done) }
                }
                items(state.days, key = { it.date.toString() }) { day ->
                    StayDayCard(
                        day = day,
                        today = state.today,
                        signals = state.signals,
                        onChoose = { onChooseCanyon(stay.id, day.date) },
                        onOpen = { outing -> onOpenOuting(OutingPlanRequest(outing.canyonId, outing.id)) },
                        onChange = { outing ->
                            viewModel.removeOuting(outing)
                            onChooseCanyon(stay.id, day.date)
                        },
                        onRemove = viewModel::removeOuting,
                    )
                }
            }
        }
    }
}

@Composable
private fun StayHeader(stay: Stay, weatherProblem: StayWeatherProblem?) {
    val colors = LocalDcColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stayRangeLabel(stay), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.stay_intro), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        weatherProblem?.let { problem ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag(TestTags.STAY_WEATHER_PROBLEM),
            ) {
                Icon(Icons.Default.CloudOff, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(
                        when (problem) {
                            StayWeatherProblem.OFFLINE -> R.string.stay_weather_offline
                            StayWeatherProblem.FAILED -> R.string.stay_weather_failed
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
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

/** "Mardi 14 juillet". */
@Composable
internal fun stayDayLabel(date: LocalDate): String {
    val locale = LocalLocale.current.platformLocale
    return DateTimeFormatter.ofPattern("EEEE d MMMM", locale).format(date).replaceFirstChar { it.titlecase(locale) }
}

/** "lun. 26 oct.". */
@Composable
internal fun shortDayLabel(date: LocalDate): String {
    val locale = LocalLocale.current.platformLocale
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale).format(date)
}

/** The canyons done on the days of the stay already past. */
@Composable
private fun StayDoneCard(done: List<LogbookEntry>) {
    val colors = LocalDcColors.current
    DcCard(modifier = Modifier.fillMaxWidth().testTag(TestTags.STAY_DONE), contentPadding = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.stay_done_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            done.forEach { entry ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.riskLow, modifier = Modifier.size(16.dp))
                    Text(entry.canyonName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(shortDayLabel(entry.date), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun StayDayCard(
    day: StayDay,
    today: LocalDate,
    signals: Map<Long, StayOutingSignals>,
    onChoose: () -> Unit,
    onOpen: (PlannedOuting) -> Unit,
    onChange: (PlannedOuting) -> Unit,
    onRemove: (PlannedOuting) -> Unit,
) {
    val colors = LocalDcColors.current
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    DcCard(modifier = Modifier.fillMaxWidth().testTag(TestTags.stayDay(day.date.toString())), contentPadding = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stayDayLabel(day.date), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (day.date == today) {
                        Text(
                            stringResource(R.string.outing_day_today),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary,
                        )
                    }
                }
                day.weather?.let { weather ->
                    Icon(
                        weatherIcon(weather.condition),
                        contentDescription = stringResource(weatherConditionRes(weather.condition)),
                        tint = if (weather.condition ==
                            WeatherCondition.THUNDERSTORM
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            colors.textSecondary
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            when {
                day.weather?.condition == WeatherCondition.THUNDERSTORM -> SignalLine(
                    icon = Icons.Default.WarningAmber,
                    text = stringResource(R.string.outing_conditions_storm),
                )

                day.weather != null -> Text(
                    forecastText(resources, day.weather, locale),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )

                day.forecastFrom != null -> Text(
                    stringResource(R.string.stay_weather_later, shortDayLabel(day.forecastFrom)),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            day.done.forEach { entry ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.riskLow, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.stay_day_done, entry.canyonName), style = MaterialTheme.typography.bodyMedium)
                }
            }
            day.outings.forEach { outing ->
                StayOutingRow(
                    outing = outing,
                    signals = signals[outing.id] ?: StayOutingSignals(),
                    today = today,
                    onClick = { onOpen(outing) },
                    onChange = { onChange(outing) },
                    onRemove = { onRemove(outing) },
                )
            }
            if (day.outings.isEmpty() && day.done.isEmpty()) {
                TextButton(
                    onClick = onChoose,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier.testTag(TestTags.stayChoose(day.date.toString())),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(stringResource(R.string.stay_choose_canyon), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun SignalLine(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun StayOutingRow(
    outing: PlannedOuting,
    signals: StayOutingSignals,
    today: LocalDate,
    onClick: () -> Unit,
    onChange: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalDcColors.current
    val locale = LocalLocale.current.platformLocale
    val zone = ZoneId.systemDefault()
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    val departure = (outing.departure ?: outing.start).atZone(zone).toLocalTime().format(time)
    val back = outing.expectedBack.atZone(zone).toLocalTime().format(time)
    var menu by remember { mutableStateOf(false) }
    DcCard(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(TestTags.outingRow(outing.id)), contentPadding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Hiking, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(outing.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                val times = if (outing.departure != null) R.string.outings_row_times else R.string.outings_row_times_walk
                Text(stringResource(times, departure, back), style = MaterialTheme.typography.bodySmall)
                if (signals.endsAtNight) SignalLine(icon = Icons.Default.NightsStay, text = stringResource(R.string.stay_outing_night))
                signals.lastReport?.let { report ->
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
                Text(
                    stringResource(if (outing.tracked) R.string.stay_outing_tracked else R.string.stay_outing_draft),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (outing.tracked) MaterialTheme.colorScheme.primary else colors.textMuted,
                )
            }
            // A followed outing has notifications and maybe a relative waiting: it changes from its plan.
            if (!outing.tracked) {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag(TestTags.stayOutingMenu(outing.id))) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_actions))
                    }
                    DcDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DcDropdownMenuItem(
                            text = stringResource(R.string.stay_outing_change),
                            icon = Icons.Default.SwapHoriz,
                            onClick = {
                                menu = false
                                onChange()
                            },
                        )
                        DcDropdownMenuItem(
                            text = stringResource(R.string.stay_outing_remove),
                            icon = Icons.Default.Delete,
                            onClick = {
                                menu = false
                                onRemove()
                            },
                        )
                    }
                }
            }
        }
    }
}
