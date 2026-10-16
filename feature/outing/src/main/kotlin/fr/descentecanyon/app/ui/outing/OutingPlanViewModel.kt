package fr.descentecanyon.app.ui.outing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonDuration
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.DailyDebitPrediction
import fr.descentecanyon.app.domain.model.DailyWeather
import fr.descentecanyon.app.domain.model.DaylightCheck
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.EmergencyNumber
import fr.descentecanyon.app.domain.model.EmergencyNumbers
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.OutingTimeline
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.SunCalculator
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.usecase.EndOutingUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonDebitPredictionsUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonPreviewUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonWeatherUseCase
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the user chose: the day, the time they leave the car and how long the relative waits. */
data class OutingChoices(val date: LocalDate, val startTime: LocalTime, val alertMarginHours: Int = DEFAULT_ALERT_MARGIN_HOURS) {
    companion object {
        const val DEFAULT_ALERT_MARGIN_HOURS = 2
        val ALERT_MARGINS = listOf(1, 2, 3)
    }
}

data class OutingPlanUiState(
    val canyon: CanyonDetail? = null,
    val isLoading: Boolean = true,
    val choices: OutingChoices,
    val today: LocalDate,
    val approach: CanyonDuration = CanyonDuration.Unknown,
    val descent: CanyonDuration = CanyonDuration.Unknown,
    val back: CanyonDuration = CanyonDuration.Unknown,
    val timeline: OutingTimeline? = null,
    val sunset: LocalTime? = null,
    val daylight: DaylightCheck = DaylightCheck.Unknown,
    /** Forecast of the chosen day, when the forecast covers it. */
    val dayWeather: DailyWeather? = null,
    val estimate: DailyDebitPrediction? = null,
    val lastReport: Debit? = null,
    val emergencyNumbers: List<EmergencyNumber> = listOf(EmergencyNumbers.EUROPEAN),
    val downstreamParking: GeoPoint? = null,
    val upstreamParking: GeoPoint? = null,
    /** The outing saved for this canyon, if any. */
    val savedOuting: PlannedOuting? = null,
    val alertAt: LocalDateTime? = null,
    val expectedBack: LocalDateTime? = null,
)

/**
 * Plans an outing from the durations of the sheet: timeline, daylight left, conditions of the day,
 * message for a relative and reminder at the expected return. Works offline from the catalog, the
 * last forecast downloaded and a sunset computed on the device.
 */
@HiltViewModel
class OutingPlanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getCanyonPreviewUseCase: GetCanyonPreviewUseCase,
    private val getCanyonWeatherUseCase: GetCanyonWeatherUseCase,
    private val getCanyonDebitPredictionsUseCase: GetCanyonDebitPredictionsUseCase,
    private val debitRepository: DebitRepository,
    private val outingRepository: OutingRepository,
    private val endOutingUseCase: EndOutingUseCase,
    private val connectivityObserver: ConnectivityObserver,
    private val clock: Clock,
) : ViewModel() {

    private val canyonId: Int = savedStateHandle["canyonId"] ?: throw IllegalArgumentException("canyonId is required")

    private val choices = MutableStateFlow(defaultChoices(LocalDateTime.now(clock)))
    private val sources = MutableStateFlow(Sources())

    val uiState: StateFlow<OutingPlanUiState> = combine(choices, sources, ::buildState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, buildState(choices.value, sources.value))

    init {
        load()
        viewModelScope.launch {
            outingRepository.observeCurrent().collect { outing -> sources.update { it.copy(outing = outing) } }
        }
        viewModelScope.launch {
            debitRepository.observeLatestKnownDebits(setOf(canyonId)).collect { debits ->
                sources.update { it.copy(lastReport = debits[canyonId]) }
            }
        }
    }

    fun selectDate(date: LocalDate) = choices.update { it.copy(date = date) }

    fun selectStartTime(time: LocalTime) = choices.update { it.copy(startTime = time) }

    fun selectAlertMargin(hours: Int) = choices.update { it.copy(alertMarginHours = hours) }

    /** Remembers the outing: reminder at the expected return, card on the home screen. */
    fun saveOuting() {
        // From the sources: the derived state may not reflect a choice made just before.
        val state = buildState(choices.value, sources.value)
        val canyon = state.canyon ?: return
        val start = LocalDateTime.of(state.choices.date, state.choices.startTime)
        val expectedBack = state.expectedBack ?: return
        val alertAt = state.alertAt ?: return
        viewModelScope.launch {
            outingRepository.save(
                PlannedOuting(
                    canyonId = canyonId,
                    canyonName = canyon.canyon.nom,
                    start = start.toInstant(),
                    expectedBack = expectedBack.toInstant(),
                    alertAt = alertAt.toInstant(),
                ),
            )
        }
    }

    /** Back from the outing: it goes to the logbook and its notifications are cancelled. */
    fun markBack() {
        viewModelScope.launch { endOutingUseCase(canyonId) }
    }

    fun cancelOuting() {
        viewModelScope.launch { outingRepository.clear() }
    }

    private fun load() {
        viewModelScope.launch {
            val detail = getCanyonPreviewUseCase(canyonId).getOrNull()
            sources.update { it.copy(canyon = detail, isLoading = false) }
            detail ?: return@launch
            val weather = if (connectivityObserver.isCurrentlyOnline()) {
                getCanyonWeatherUseCase(detail).getOrNull() ?: getCanyonWeatherUseCase.cached(canyonId)
            } else {
                getCanyonWeatherUseCase.cached(canyonId)
            }
            sources.update { it.copy(weather = weather, predictions = getCanyonDebitPredictionsUseCase.cached(canyonId)) }
        }
    }

    private fun buildState(choices: OutingChoices, sources: Sources): OutingPlanUiState {
        val detail = sources.canyon
        val canyon = detail?.canyon
        val approach = CanyonDuration.parse(canyon?.tempsApproche)
        val descent = CanyonDuration.parse(canyon?.tempsDescente)
        val back = CanyonDuration.parse(canyon?.tempsRetour)
        val start = LocalDateTime.of(choices.date, choices.startTime)
        val timeline = detail?.let { OutingTimeline.compute(start, approach, descent, back) }
        val dayWeather = sources.weather?.dayOf(choices.date)
        val sunset = dayWeather?.sunset ?: detail?.let { computedSunset(it, choices.date, sources.weather) }
        val expectedBack = timeline?.backAtCar?.latest ?: start.plus(UNKNOWN_DURATION_ASSUMPTION)
        return OutingPlanUiState(
            canyon = detail,
            isLoading = sources.isLoading,
            choices = choices,
            today = LocalDate.now(clock),
            approach = approach,
            descent = descent,
            back = back,
            timeline = timeline,
            sunset = sunset,
            daylight = timeline?.let { DaylightCheck.of(it, sunset) } ?: DaylightCheck.Unknown,
            dayWeather = dayWeather,
            estimate = sources.predictions?.predictions?.firstOrNull { it.date == choices.date },
            lastReport = sources.lastReport,
            emergencyNumbers = canyon?.pays?.let(EmergencyNumbers::forCountries) ?: listOf(EmergencyNumbers.EUROPEAN),
            downstreamParking = detail?.geoPoints?.firstOrNull { it.type == GeoPointType.PARKING_AVAL },
            upstreamParking = detail?.geoPoints?.firstOrNull { it.type == GeoPointType.PARKING_AMONT },
            savedOuting = sources.outing?.takeIf { it.canyonId == canyonId },
            alertAt = expectedBack.plusHours(choices.alertMarginHours.toLong()),
            expectedBack = expectedBack,
        )
    }

    /** Offline sunset at the start of the canyon (parking, else entry, else any point). */
    private fun computedSunset(detail: CanyonDetail, date: LocalDate, weather: CanyonWeather?): LocalTime? {
        val point = detail.geoPoints.firstOrNull { it.type == GeoPointType.PARKING_AMONT }
            ?: detail.geoPoints.firstOrNull { it.type == GeoPointType.ENTREE }
            ?: detail.geoPoints.firstOrNull()
            ?: return null
        val zoneId = weather?.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: clock.zone
        return SunCalculator.sunTimes(date, point.latitude, point.longitude, zoneId).sunset
    }

    private fun LocalDateTime.toInstant(): Instant = atZone(clock.zone).toInstant()

    private data class Sources(
        val canyon: CanyonDetail? = null,
        val isLoading: Boolean = true,
        val weather: CanyonWeather? = null,
        val predictions: CanyonDebitPredictions? = null,
        val lastReport: Debit? = null,
        val outing: PlannedOuting? = null,
    )

    companion object {
        /** When the sheet does not give every duration, the relative is told a full day. */
        val UNKNOWN_DURATION_ASSUMPTION: Duration = Duration.ofHours(8)

        /** Today at the next quarter hour in the morning, else tomorrow at 9:00. */
        internal fun defaultChoices(now: LocalDateTime): OutingChoices = if (now.hour < LATEST_SAME_DAY_START_HOUR) {
            val minutes = now.minute
            val next = now.withSecond(0).withNano(0).plusMinutes(((15 - minutes % 15) % 15).toLong())
            OutingChoices(date = next.toLocalDate(), startTime = next.toLocalTime())
        } else {
            OutingChoices(date = now.toLocalDate().plusDays(1), startTime = LocalTime.of(9, 0))
        }

        private const val LATEST_SAME_DAY_START_HOUR = 13
    }
}
