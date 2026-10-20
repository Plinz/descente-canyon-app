package fr.descentecanyon.app.ui.outing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import fr.descentecanyon.app.domain.model.WeatherCondition
import fr.descentecanyon.app.domain.model.haversineKm
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.DriveTimeRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.PlaceRepository
import fr.descentecanyon.app.domain.usecase.GetCanyonWeatherUseCase
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.components.debitLevelColor
import fr.descentecanyon.app.ui.components.debitLevelLabel
import fr.descentecanyon.app.ui.components.relativeDayLabel
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcEmptyState
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.test.TestTags
import java.time.Clock
import java.time.LocalDate
import java.time.format.TextStyle
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

/** A canyon that could fill a day of the stay. */
data class StayCandidate(
    val canyon: CanyonSearchItem,
    val drive: DriveTime,
    val lastReport: Debit? = null,
    /** Forecast by day, when downloaded. */
    val weather: Map<LocalDate, DailyWeather> = emptyMap(),
)

data class StayPlannerUiState(
    val isLoading: Boolean = true,
    val place: Place? = null,
    val today: LocalDate,
    val days: List<LocalDate> = emptyList(),
    val radiusKm: Int = DEFAULT_RADIUS_KM,
    val filter: StayFilter = StayFilter.ALL,
    val candidates: List<StayCandidate> = emptyList(),
    /** Outings planned on the days of the stay, by canyon and day. */
    val planned: Map<Pair<Int, LocalDate>, PlannedOuting> = emptyMap(),
    val isLoadingWeather: Boolean = false,
) {
    companion object {
        const val DEFAULT_RADIUS_KM = 40
        val RADII_KM = listOf(20, 40, 60)
    }
}

/**
 * Prepares several outings from one place: the canyons around it, nearest by road first, with the
 * forecast of each day of the stay, and the outings already planned. A day of a canyon opens its
 * plan from that place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StayPlannerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    placeRepository: PlaceRepository,
    private val canyonRepository: CanyonRepository,
    logbookRepository: LogbookRepository,
    outingRepository: OutingRepository,
    private val driveTimeRepository: DriveTimeRepository,
    private val debitRepository: DebitRepository,
    private val getCanyonWeatherUseCase: GetCanyonWeatherUseCase,
    private val connectivityObserver: ConnectivityObserver,
    private val clock: Clock,
) : ViewModel() {

    private val placeId: Long = savedStateHandle["placeId"] ?: 0L
    private val radiusKm = MutableStateFlow(StayPlannerUiState.DEFAULT_RADIUS_KM)
    private val filter = MutableStateFlow(StayFilter.ALL)
    private val weather = MutableStateFlow<Map<Int, CanyonWeather>>(emptyMap())
    private val loadingWeather = MutableStateFlow(false)
    private val storedDrives = MutableStateFlow<Map<Int, DriveTime>>(emptyMap())

    private val place = placeRepository.observeAll().map { places -> places.firstOrNull { it.id == placeId } }.distinctUntilChanged()

    private val nearby =
        combine(place, canyonRepository.observeSearchCatalog(), logbookRepository.observeDescendedCanyonIds(), radiusKm, filter) {
                place,
                catalog,
                descended,
                radius,
                filter,
            ->
            place ?: return@combine emptyList<Pair<CanyonSearchItem, Double>>()
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
                .toList()
        }

    private val candidates = combine(place, nearby, storedDrives) { place, nearby, stored ->
        place ?: return@combine emptyList()
        nearby.map { (item, _) ->
            StayCandidate(
                canyon = item,
                drive =
                    stored[item.id]
                        ?: DriveTime.estimate(place.latitude, place.longitude, item.representativeLat!!, item.representativeLng!!),
            )
        }.sortedBy { it.drive.minutes }
    }

    private val reports = candidates.map { list -> list.map { it.canyon.id }.toSet() }.distinctUntilChanged()
        .flatMapLatest { ids -> if (ids.isEmpty()) flowOf(emptyMap()) else debitRepository.observeLatestKnownDebits(ids) }

    val uiState: StateFlow<StayPlannerUiState> = combine(
        combine(place, candidates, reports, weather, ::CandidateSources),
        outingRepository.observeAll(),
        radiusKm,
        filter,
        loadingWeather,
    ) { sources, outings, radius, filter, isLoadingWeather ->
        val today = LocalDate.now(clock)
        val days = stayDays(sources.place, today)
        StayPlannerUiState(
            isLoading = false,
            place = sources.place,
            today = today,
            days = days,
            radiusKm = radius,
            filter = filter,
            candidates = sources.candidates.map { candidate ->
                candidate.copy(
                    lastReport = sources.reports[candidate.canyon.id],
                    weather = sources.weather[candidate.canyon.id]?.daily?.associateBy { it.date }.orEmpty(),
                )
            },
            planned = outings.associateBy { it.canyonId to it.start.atZone(clock.zone).toLocalDate() }.filterKeys { it.second in days },
            isLoadingWeather = isLoadingWeather,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StayPlannerUiState(today = LocalDate.now(clock)))

    init {
        viewModelScope.launch { storedDrives.value = driveTimeRepository.storedFrom(placeId) }
        viewModelScope.launch {
            candidates.map { list ->
                list.take(WEATHER_CANDIDATES).map { it.canyon.id }
            }.distinctUntilChanged().collectLatest(::loadWeather)
        }
    }

    fun selectRadius(km: Int) = radiusKm.update { km }

    fun selectFilter(value: StayFilter) = filter.update { value }

    /** The forecast kept on the device first, then downloaded for the canyons that have none. */
    private suspend fun loadWeather(ids: List<Int>) {
        val missing = ids.filterNot { it in weather.value }
        missing.forEach { id -> getCanyonWeatherUseCase.cached(id)?.let { cached -> weather.update { it + (id to cached) } } }
        if (!connectivityObserver.isCurrentlyOnline()) return
        val toDownload = ids.filterNot { it in weather.value }
        if (toDownload.isEmpty()) return
        loadingWeather.value = true
        try {
            toDownload.forEach { id ->
                val detail = canyonRepository.getCanyonPreview(id).getOrNull() ?: return@forEach
                getCanyonWeatherUseCase(detail).getOrNull()?.let { downloaded -> weather.update { it + (id to downloaded) } }
            }
        } finally {
            loadingWeather.value = false
        }
    }

    private data class CandidateSources(
        val place: Place?,
        val candidates: List<StayCandidate>,
        val reports: Map<Int, Debit>,
        val weather: Map<Int, CanyonWeather>,
    )

    companion object {
        const val MAX_CANDIDATES = 30
        const val WEATHER_CANDIDATES = 12

        /** The forecast covers today and the next five days. */
        const val FORECAST_DAYS = 6L
        private const val DEFAULT_STAY_DAYS = 7L
        private const val MAX_STAY_DAYS = 14L
        private const val STOP_TIMEOUT_MS = 5_000L

        /** The days of the stay still to come, else the coming week. */
        internal fun stayDays(place: Place?, today: LocalDate): List<LocalDate> {
            val start = maxOf(place?.stayStart ?: today, today)
            val end = place?.stayEnd?.takeIf { place.stayStart != null && !it.isBefore(start) } ?: start.plusDays(DEFAULT_STAY_DAYS - 1)
            val last = minOf(end, start.plusDays(MAX_STAY_DAYS - 1))
            return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StayPlannerScreen(
    onBackClick: () -> Unit,
    onPlanDay: (OutingPlanRequest) -> Unit,
    onEditPlace: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StayPlannerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)
    var weekIndex by rememberSaveable { mutableIntStateOf(0) }
    val weeks = state.days.chunked(DAYS_PER_ROW)
    val visibleDays = weeks.getOrNull(weekIndex) ?: weeks.firstOrNull().orEmpty()
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
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.fillMaxHeight().width(contentWidth).testTag(TestTags.STAY_LIST),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            state.place?.let { stayLabel(it) } ?: stringResource(R.string.stay_no_dates),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.clickable(onClick = onEditPlace),
                        )
                        Text(
                            stringResource(R.string.stay_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalDcColors.current.textSecondary,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StayPlannerUiState.RADII_KM.forEach { km ->
                                FilterChip(
                                    selected = state.radiusKm == km,
                                    onClick = { viewModel.selectRadius(km) },
                                    label = { Text(stringResource(R.string.stay_radius, km)) },
                                )
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StayFilter.entries.forEach { filter ->
                                FilterChip(
                                    selected = state.filter == filter,
                                    onClick = { viewModel.selectFilter(filter) },
                                    label = { Text(stayFilterLabel(filter)) },
                                )
                            }
                        }
                    }
                }
                item(key = "days") {
                    DaysHeader(
                        days = visibleDays,
                        hasPrevious = weekIndex > 0,
                        hasNext = weekIndex < weeks.lastIndex,
                        onPrevious = { weekIndex-- },
                        onNext = { weekIndex++ },
                        isLoadingWeather = state.isLoadingWeather,
                    )
                }
                if (!state.isLoading && state.candidates.isEmpty()) {
                    item(key = "empty") {
                        DcEmptyState(title = stringResource(R.string.stay_empty), body = stringResource(R.string.stay_empty_hint))
                    }
                }
                items(state.candidates, key = { it.canyon.id }) { candidate ->
                    CandidateRow(
                        candidate = candidate,
                        days = visibleDays,
                        today = state.today,
                        planned = state.planned,
                        onDay = { day ->
                            val outing = state.planned[candidate.canyon.id to day]
                            onPlanDay(OutingPlanRequest(candidate.canyon.id, outing?.id ?: 0, day, state.place?.id ?: 0))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DaysHeader(
    days: List<LocalDate>,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    isLoadingWeather: Boolean,
) {
    val locale = LocalLocale.current.platformLocale
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.stay_days),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (isLoadingWeather) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            IconButton(onClick = onPrevious, enabled = hasPrevious) {
                Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(R.string.stay_previous_days))
            }
            IconButton(onClick = onNext, enabled = hasNext) {
                Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.stay_next_days))
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            days.forEach { day ->
                Text(
                    "${day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)}\n${day.dayOfMonth}",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(DAYS_PER_ROW - days.size) { Box(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: StayCandidate,
    days: List<LocalDate>,
    today: LocalDate,
    planned: Map<Pair<Int, LocalDate>, PlannedOuting>,
    onDay: (LocalDate) -> Unit,
) {
    val colors = LocalDcColors.current
    val canyon = candidate.canyon
    DcCard(modifier = Modifier.fillMaxWidth().testTag(TestTags.stayCandidate(canyon.id)), contentPadding = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                Text(canyon.nom, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(canyon.cotation)
                        append(" · ")
                        append(
                            if (candidate.drive.source == DriveTimeSource.ESTIMATE) {
                                stringResource(R.string.stay_drive_estimate, formatMinutes(candidate.drive.minutes.toLong()))
                            } else {
                                stringResource(R.string.stay_drive, formatMinutes(candidate.drive.minutes.toLong()))
                            },
                        )
                        if (canyon.isDescended) append(" · ").append(stringResource(R.string.stay_descended))
                    },
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
            Row(modifier = Modifier.fillMaxWidth()) {
                days.forEach { day ->
                    DayCell(
                        weather = candidate.weather[day],
                        isPlanned = (canyon.id to day) in planned,
                        isBeyondForecast = day.isAfter(today.plusDays(StayPlannerViewModel.FORECAST_DAYS - 1)),
                        onClick = { onDay(day) },
                        modifier = Modifier.weight(1f).testTag(TestTags.stayDay(canyon.id, day.toString())),
                    )
                }
                repeat(DAYS_PER_ROW - days.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** A day of a canyon: its weather (storm in red), planned or not; a tap plans it. */
@Composable
private fun DayCell(
    weather: DailyWeather?,
    isPlanned: Boolean,
    isBeyondForecast: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDcColors.current
    val storm = weather?.condition == WeatherCondition.THUNDERSTORM
    val background = when {
        isPlanned -> MaterialTheme.colorScheme.primary
        storm -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
        else -> colors.surfaceRaised
    }
    val content = if (isPlanned) MaterialTheme.colorScheme.onPrimary else colors.textPrimary
    Column(
        modifier = modifier
            .padding(2.dp)
            .heightIn(min = 52.dp)
            .background(background, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            isPlanned -> Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.stay_planned),
                tint = content,
                modifier = Modifier.size(20.dp),
            )

            weather != null -> {
                Icon(
                    weatherIcon(weather.condition),
                    contentDescription = stringResource(weatherConditionRes(weather.condition)),
                    tint = if (storm) MaterialTheme.colorScheme.error else colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    weather.precipitationSumMm?.let { "${it.roundToInt()} mm" } ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
            }

            else -> Text(if (isBeyondForecast) "·" else "–", style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
        }
    }
}

private fun weatherIcon(condition: WeatherCondition): ImageVector = when (condition) {
    WeatherCondition.CLEAR -> Icons.Default.WbSunny
    WeatherCondition.PARTLY_CLOUDY -> Icons.Default.WbCloudy
    WeatherCondition.CLOUDY, WeatherCondition.FOG -> Icons.Default.Cloud
    WeatherCondition.DRIZZLE, WeatherCondition.RAIN -> Icons.Default.WaterDrop
    WeatherCondition.SNOW -> Icons.Default.AcUnit
    WeatherCondition.THUNDERSTORM -> Icons.Default.Thunderstorm
    WeatherCondition.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline
}

@Composable
private fun stayFilterLabel(filter: StayFilter): String = stringResource(
    when (filter) {
        StayFilter.ALL -> R.string.stay_filter_all
        StayFilter.FAVORITES -> R.string.stay_filter_favorites
        StayFilter.NOT_DESCENDED -> R.string.stay_filter_not_descended
    },
)

private const val DAYS_PER_ROW = 7
