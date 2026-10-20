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
import fr.descentecanyon.app.domain.model.DriveTime
import fr.descentecanyon.app.domain.model.EmergencyNumber
import fr.descentecanyon.app.domain.model.EmergencyNumbers
import fr.descentecanyon.app.domain.model.GearContext
import fr.descentecanyon.app.domain.model.GearGroup
import fr.descentecanyon.app.domain.model.GearItem
import fr.descentecanyon.app.domain.model.GearKey
import fr.descentecanyon.app.domain.model.GearListSettings
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.GroupLevel
import fr.descentecanyon.app.domain.model.MessageAudience
import fr.descentecanyon.app.domain.model.MessageSection
import fr.descentecanyon.app.domain.model.OutingAnchor
import fr.descentecanyon.app.domain.model.OutingChoices
import fr.descentecanyon.app.domain.model.OutingPace
import fr.descentecanyon.app.domain.model.OutingPhase
import fr.descentecanyon.app.domain.model.OutingSchedule
import fr.descentecanyon.app.domain.model.OutingScheduleInput
import fr.descentecanyon.app.domain.model.OutingSettings
import fr.descentecanyon.app.domain.model.PersonalPace
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.Shuttle
import fr.descentecanyon.app.domain.model.SunCalculator
import fr.descentecanyon.app.domain.model.defaultFor
import fr.descentecanyon.app.domain.model.gearChecklist
import fr.descentecanyon.app.domain.model.meetingParking
import fr.descentecanyon.app.domain.repository.CanyonNotesRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.DriveTimeRepository
import fr.descentecanyon.app.domain.repository.OutingPreferencesRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.PlaceRepository
import fr.descentecanyon.app.domain.usecase.EndOutingUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonDebitPredictionsUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonPreviewUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonWeatherUseCase
import fr.descentecanyon.app.domain.usecase.GetDriveTimeUseCase
import fr.descentecanyon.app.domain.usecase.GetPersonalPaceUseCase
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The drive time known for a place and a parking. */
data class DriveState(val placeId: Long, val destination: GeoPoint, val time: DriveTime)

data class OutingPlanUiState(
    val canyon: CanyonDetail? = null,
    val isLoading: Boolean = true,
    val choices: OutingChoices,
    val today: LocalDate,
    val now: Instant,
    val settings: OutingSettings = OutingSettings.DEFAULT,
    val pace: OutingPace,
    val personalPace: PersonalPace? = null,
    val places: List<Place> = emptyList(),
    /** The starting place chosen, if any. */
    val place: Place? = null,
    val drive: DriveTime? = null,
    /** The sheet asks for a shuttle of this length. */
    val sheetShuttle: Shuttle? = null,
    val withShuttle: Boolean = false,
    /** Where the cars go: the meeting point. */
    val meetingParking: GeoPoint? = null,
    val approach: CanyonDuration = CanyonDuration.Unknown,
    val descent: CanyonDuration = CanyonDuration.Unknown,
    val back: CanyonDuration = CanyonDuration.Unknown,
    val schedule: OutingSchedule? = null,
    val sunset: LocalTime? = null,
    val daylight: DaylightCheck = DaylightCheck.Unknown,
    /** Forecast of the chosen day, when the forecast covers it. */
    val dayWeather: DailyWeather? = null,
    val estimate: DailyDebitPrediction? = null,
    val lastReport: Debit? = null,
    val emergencyNumbers: List<EmergencyNumber> = listOf(EmergencyNumbers.EUROPEAN),
    val downstreamParking: GeoPoint? = null,
    val upstreamParking: GeoPoint? = null,
    /** The outing as saved, if it is. */
    val savedOuting: PlannedOuting? = null,
    /** The saved outing differs from the plan on screen. */
    val hasUnsavedChanges: Boolean = false,
    /** Most likely return to the car. */
    val expectedBack: LocalDateTime? = null,
    /** Return to the car if every step takes long. */
    val latestBack: LocalDateTime? = null,
    val alertAt: LocalDateTime? = null,
    /** The user's memo of the canyon (access, gear, traps): read again while preparing. */
    val memo: String? = null,
    val gearList: GearListSettings = GearListSettings(),
    val gear: List<GearItem> = emptyList(),
    val messageSections: Map<MessageAudience, Set<MessageSection>> = MessageAudience.entries.associateWith { it.defaultSections },
    /** The relative was sent another alert time than the one planned now. */
    val alertChangedSinceInformed: Boolean = false,
) {
    val withBeginners: Boolean get() = choices.groupLevel != GroupLevel.AUTONOMOUS
}

/**
 * Plans an outing from the durations of the sheet stretched for the group: the whole day from the
 * starting place, daylight left, conditions of the day, gear, messages for a relative, the team or
 * beginners, and the follow-up of the outing once saved. Works offline from the catalog, the last
 * forecast downloaded, a sunset computed on the device and an estimated drive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OutingPlanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getCanyonPreviewUseCase: GetCanyonPreviewUseCase,
    private val getCanyonWeatherUseCase: GetCanyonWeatherUseCase,
    private val getCanyonDebitPredictionsUseCase: GetCanyonDebitPredictionsUseCase,
    private val debitRepository: DebitRepository,
    private val outingRepository: OutingRepository,
    private val canyonNotesRepository: CanyonNotesRepository,
    private val endOutingUseCase: EndOutingUseCase,
    private val placeRepository: PlaceRepository,
    private val outingPreferencesRepository: OutingPreferencesRepository,
    private val getPersonalPaceUseCase: GetPersonalPaceUseCase,
    private val getDriveTimeUseCase: GetDriveTimeUseCase,
    private val driveTimeRepository: DriveTimeRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val clock: Clock,
) : ViewModel() {

    private val canyonId: Int = savedStateHandle["canyonId"] ?: throw IllegalArgumentException("canyonId is required")
    private val requestedOutingId: Long = savedStateHandle["outingId"] ?: 0L
    private val requestedDate: LocalDate? = savedStateHandle.get<String>("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    private val requestedPlaceId: Long = savedStateHandle["placeId"] ?: 0L

    private val choices = MutableStateFlow<OutingChoices?>(null)
    private val sources = MutableStateFlow(Sources())
    private val driveRefresh = MutableStateFlow(0)

    val uiState: StateFlow<OutingPlanUiState> = combine(choices, sources, ::buildState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, buildState(choices.value, sources.value))

    init {
        load()
        viewModelScope.launch { outingRepository.observeAll().collect { outings -> sources.update { it.copy(outings = outings) } } }
        viewModelScope.launch { canyonNotesRepository.observeNote(canyonId).collect { memo -> sources.update { it.copy(memo = memo) } } }
        viewModelScope.launch {
            debitRepository.observeLatestKnownDebits(setOf(canyonId)).collect { debits ->
                sources.update { it.copy(lastReport = debits[canyonId]) }
            }
        }
        viewModelScope.launch { placeRepository.observeAll().collect { places -> sources.update { it.copy(places = places) } } }
        viewModelScope.launch { outingPreferencesRepository.observeSettings().collect { s -> sources.update { it.copy(settings = s) } } }
        viewModelScope.launch { outingPreferencesRepository.observeGearList().collect { g -> sources.update { it.copy(gearList = g) } } }
        viewModelScope.launch {
            outingPreferencesRepository.observeMessageSections().collect { s -> sources.update { it.copy(messageSections = s) } }
        }
        viewModelScope.launch { getPersonalPaceUseCase().let { pace -> sources.update { it.copy(personalPace = pace) } } }
        viewModelScope.launch { initChoices() }
        viewModelScope.launch { followDrive() }
    }

    fun selectDate(date: LocalDate) = updateChoices { it.copy(date = date) }

    fun selectAnchorTime(time: LocalTime) = updateChoices { it.copy(anchorTime = time) }

    fun selectAnchor(anchor: OutingAnchor) = updateChoices { it.copy(anchor = anchor) }

    /** Null: no starting place, the day starts at the parking. */
    fun selectPlace(placeId: Long?) = updateChoices { choices ->
        choices.copy(placeId = placeId, anchor = if (placeId == null) OutingAnchor.ARRIVE_PARKING else choices.anchor)
    }

    fun selectGroupSize(size: Int) = updateChoices { it.copy(groupSize = size.coerceIn(OutingSettings.GROUP_SIZE_RANGE)) }

    fun selectGroupLevel(level: GroupLevel) = updateChoices { it.copy(groupLevel = level) }

    fun selectShuttle(withShuttle: Boolean) = updateChoices { it.copy(withShuttle = withShuttle) }

    fun selectAlertMargin(hours: Int) = updateChoices { it.copy(alertMarginHours = hours) }

    /** Lengthens or shortens a step for this outing; 0 brings back the computed time. */
    fun adjustPhase(phase: OutingPhase, minutes: Int) = updateChoices { choices ->
        choices.copy(adjustments = if (minutes == 0) choices.adjustments - phase else choices.adjustments + (phase to minutes))
    }

    /** The user's own drive time from the place to the parking, kept for the next outings; null forgets it. */
    fun setManualDrive(minutes: Int?) {
        val state = uiState.value
        val place = state.place ?: return
        val destination = state.meetingParking ?: return
        viewModelScope.launch {
            if (minutes == null) {
                driveTimeRepository.clear(place.id, canyonId)
            } else {
                driveTimeRepository.setManual(place.id, canyonId, destination, minutes.coerceIn(DriveTime.MANUAL_RANGE))
            }
            driveRefresh.update { it + 1 }
        }
    }

    /** Checked items stay with the saved outing at once: packing happens in several goes. */
    fun toggleGear(id: String) {
        updateChoices { choices ->
            choices.copy(checkedGear = if (id in choices.checkedGear) choices.checkedGear - id else choices.checkedGear + id)
        }
        persistMessageFields()
    }

    fun setNote(note: String) {
        updateChoices { it.copy(note = note.take(MAX_NOTE_LENGTH)) }
        persistMessageFields()
    }

    fun setMeetAtPlace(meetAtPlace: Boolean) {
        updateChoices { it.copy(meetAtPlace = meetAtPlace) }
        persistMessageFields()
    }

    fun hideGear(key: GearKey) = updateGearList { it.copy(hidden = it.hidden + key) }

    fun removeCustomGear(id: String) = updateGearList { list -> list.copy(custom = list.custom.filterNot { it.id == id }) }

    fun addCustomGear(group: GearGroup, label: String) {
        val text = label.trim().takeIf { it.isNotEmpty() } ?: return
        updateGearList { it.copy(custom = it.custom + GearItem.Custom("custom:${UUID.randomUUID()}", group, text)) }
    }

    fun restoreSuggestedGear() = updateGearList { it.copy(hidden = emptySet()) }

    fun saveMessageSections(audience: MessageAudience, sections: Set<MessageSection>) {
        viewModelScope.launch { outingPreferencesRepository.setMessageSections(audience, sections) }
    }

    /** Remembers the outing (or its changes): notifications during it, reminder at the return, alert for the relative. */
    fun saveOuting() = save(relativeInformed = null)

    /** The plan went to a relative: the outing is saved, so the app follows it as the relative does. */
    fun markRelativeInformed() = save(relativeInformed = true)

    /** The alert comes [minutes] later: the outing takes longer than planned. */
    fun delayAlert(minutes: Int) {
        updateChoices { it.copy(alertDelayMinutes = it.alertDelayMinutes + minutes) }
        if (sources.value.outingId > 0) save(relativeInformed = null)
    }

    /** Back from the outing: it goes to the logbook and its notifications are cancelled. */
    fun markBack() {
        val id = sources.value.outingId.takeIf { it > 0 } ?: return
        viewModelScope.launch {
            endOutingUseCase(id)
            sources.update { it.copy(outingId = 0) }
            updateChoices { it.copy(alertDelayMinutes = 0) }
        }
    }

    /** The outing will not happen: it is forgotten, the plan stays on screen. */
    fun cancelOuting() {
        val id = sources.value.outingId.takeIf { it > 0 } ?: return
        viewModelScope.launch {
            outingRepository.delete(id)
            sources.update { it.copy(outingId = 0) }
        }
    }

    private fun save(relativeInformed: Boolean?) {
        // From the sources: the derived state may not reflect a choice made just before.
        val state = buildState(choices.value, sources.value)
        val outing = state.toPlannedOuting() ?: return
        val saved = state.savedOuting
        viewModelScope.launch {
            val id = outingRepository.save(
                outing.copy(
                    id = saved?.id ?: 0,
                    relativeInformed = relativeInformed ?: saved?.relativeInformed ?: false,
                ),
            )
            sources.update { it.copy(outingId = id) }
            state.place?.let { placeRepository.markUsed(it.id) }
        }
    }

    /** Gear checks, note and meeting point only change the messages: they are kept without touching the times saved. */
    private fun persistMessageFields() {
        val saved = sources.value.outings.firstOrNull { it.id == sources.value.outingId } ?: return
        val current = choices.value ?: return
        val savedChoices = saved.choices ?: return
        viewModelScope.launch {
            outingRepository.save(
                saved.copy(
                    choices = savedChoices.copy(checkedGear = current.checkedGear, note = current.note, meetAtPlace = current.meetAtPlace),
                ),
            )
        }
    }

    private fun updateGearList(change: (GearListSettings) -> GearListSettings) {
        val updated = change(sources.value.gearList)
        sources.update { it.copy(gearList = updated) }
        viewModelScope.launch { outingPreferencesRepository.setGearList(updated) }
    }

    private fun updateChoices(change: (OutingChoices) -> OutingChoices) = choices.update { current -> current?.let(change) }

    private suspend fun initChoices() {
        val settings = outingPreferencesRepository.observeSettings().first()
        val places = placeRepository.observeAll().first()
        val outings = outingRepository.observeAll().first()
        val existing = when {
            requestedOutingId > 0 -> outings.firstOrNull { it.id == requestedOutingId } ?: outingRepository.get(requestedOutingId)
            requestedDate == null -> outings.firstOrNull { it.canyonId == canyonId }
            else -> outings.firstOrNull { it.canyonId == canyonId && it.choices?.date == requestedDate }
        }
        sources.update { it.copy(outingId = existing?.id ?: 0) }
        choices.value = existing?.let { it.choices ?: legacyChoices(it) } ?: newChoices(settings, places)
    }

    private fun newChoices(settings: OutingSettings, places: List<Place>): OutingChoices {
        val now = LocalDateTime.now(clock)
        val default = defaultChoices(now)
        val date = requestedDate ?: default.date
        val time = if (date == default.date) default.anchorTime else LocalTime.of(DEFAULT_HOUR, 0)
        val place = places.firstOrNull { it.id == requestedPlaceId } ?: places.defaultFor(date)
        return OutingChoices(
            date = date,
            anchorTime = time,
            anchor = if (place != null) OutingAnchor.LEAVE_PLACE else OutingAnchor.ARRIVE_PARKING,
            placeId = place?.id,
            groupSize = settings.defaultGroupSize,
            groupLevel = settings.defaultGroupLevel,
        )
    }

    /** An outing saved before choices were kept: its day and walk start. */
    private fun legacyChoices(outing: PlannedOuting): OutingChoices {
        val start = outing.start.atZone(clock.zone).toLocalDateTime()
        return OutingChoices(date = start.toLocalDate(), anchorTime = start.toLocalTime(), groupSize = outing.groupSize ?: 4)
    }

    /** Follows the drive of the place and parking on screen: estimate first, then the route. */
    private suspend fun followDrive() {
        // The place counts by its position: marking it used must not ask the route again.
        combine(
            uiState.map { state ->
                state.place?.copy(lastUsedAt = 0) to state.meetingParking
            }.distinctUntilChanged(),
            driveRefresh,
        ) { key, _ ->
            key
        }
            .collectLatest { (place, destination) ->
                if (place == null || destination == null) return@collectLatest
                getDriveTimeUseCase(place, canyonId, destination).collect { time ->
                    sources.update { it.copy(drive = DriveState(place.id, destination, time)) }
                }
            }
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

    private fun buildState(choices: OutingChoices?, sources: Sources): OutingPlanUiState {
        val now = clock.instant()
        val today = LocalDate.now(clock)
        val settings = sources.settings
        val current = choices ?: defaultChoices(LocalDateTime.now(clock))
        val pace = OutingPace.of(settings, current.groupSize, current.groupLevel, sources.personalPace)
        val detail = sources.canyon
        val canyon = detail?.canyon
        val approach = CanyonDuration.parse(canyon?.tempsApproche)
        val descent = CanyonDuration.parse(canyon?.tempsDescente)
        val back = CanyonDuration.parse(canyon?.tempsRetour)
        val sheetShuttle = Shuttle.parse(canyon?.navette)
        val withShuttle = sheetShuttle != null && current.withShuttle != false
        val place = sources.places.firstOrNull { it.id == current.placeId }
        val meetingParking = detail?.meetingParking(withShuttle)
        val drive = sources.drive?.takeIf { it.placeId == place?.id && it.destination == meetingParking }?.time
        val schedule = detail?.let {
            OutingSchedule.compute(
                OutingScheduleInput(
                    date = current.date,
                    anchor = current.anchor,
                    anchorTime = current.anchorTime,
                    driveMinutes = drive?.minutes,
                    shuttle = sheetShuttle?.takeIf { withShuttle },
                    approach = approach,
                    descent = descent,
                    back = back,
                    pace = pace,
                    settings = settings,
                    adjustments = current.adjustments,
                ),
            )
        }
        val dayWeather = sources.weather?.dayOf(current.date)
        val sunset = dayWeather?.sunset ?: detail?.let { computedSunset(it, current.date, sources.weather) }
        val expectedBack = schedule?.let { it.backAtCar?.estimate ?: it.walkStart.plus(UNKNOWN_DURATION_ASSUMPTION) }
        val latestBack = schedule?.let { it.backAtCar?.latest ?: it.walkStart.plus(UNKNOWN_DURATION_ASSUMPTION) }
        val alertAt = latestBack?.plusHours(current.alertMarginHours.toLong())?.plusMinutes(current.alertDelayMinutes.toLong())
        val savedOuting = sources.outings.firstOrNull { it.id == sources.outingId }
        val state = OutingPlanUiState(
            canyon = detail,
            isLoading = sources.isLoading || choices == null,
            choices = current,
            today = today,
            now = now,
            settings = settings,
            pace = pace,
            personalPace = sources.personalPace,
            places = sources.places,
            place = place,
            drive = drive,
            sheetShuttle = sheetShuttle,
            withShuttle = withShuttle,
            meetingParking = meetingParking,
            approach = approach,
            descent = descent,
            back = back,
            schedule = schedule,
            sunset = sunset,
            daylight = schedule?.let { DaylightCheck.of(it, sunset) } ?: DaylightCheck.Unknown,
            dayWeather = dayWeather,
            estimate = sources.predictions?.predictions?.firstOrNull { it.date == current.date },
            lastReport = sources.lastReport,
            emergencyNumbers = canyon?.pays?.let(EmergencyNumbers::forCountries) ?: listOf(EmergencyNumbers.EUROPEAN),
            downstreamParking = detail?.geoPoints?.firstOrNull { it.type == GeoPointType.PARKING_AVAL },
            upstreamParking = detail?.geoPoints?.firstOrNull { it.type == GeoPointType.PARKING_AMONT },
            savedOuting = savedOuting,
            expectedBack = expectedBack,
            latestBack = latestBack,
            alertAt = alertAt,
            memo = sources.memo,
            gearList = sources.gearList,
            gear = gearChecklist(
                sources.gearList,
                GearContext(
                    ropeLengthMeters = canyon?.cordeMin,
                    shuttleKm = sheetShuttle?.takeIf { withShuttle }?.distanceKm?.roundToInt(),
                    longestOnFootMinutes = schedule?.longestOnFoot?.toMinutes(),
                ),
            ),
            messageSections = sources.messageSections,
        )
        return state.copy(
            hasUnsavedChanges = savedOuting != null && state.toPlannedOuting()?.differsFrom(savedOuting) == true,
            alertChangedSinceInformed =
                savedOuting?.relativeInformed == true && alertAt != null && savedOuting.alertAt != alertAt.toInstant(),
        )
    }

    private fun OutingPlanUiState.toPlannedOuting(): PlannedOuting? {
        val canyon = canyon?.canyon ?: return null
        val schedule = schedule ?: return null
        return PlannedOuting(
            canyonId = canyon.id,
            canyonName = canyon.nom,
            start = schedule.walkStart.toInstant(),
            expectedBack = expectedBack?.toInstant() ?: return null,
            alertAt = alertAt?.toInstant() ?: return null,
            latestBack = latestBack?.toInstant() ?: return null,
            departure = schedule.leavePlace?.toInstant(),
            groupSize = choices.groupSize,
            choices = choices,
        )
    }

    /** What the saved outing would change: times, group and choices; gear checks, note and meeting point are kept as they go. */
    private fun PlannedOuting.differsFrom(saved: PlannedOuting): Boolean {
        fun OutingChoices?.comparable() = this?.copy(checkedGear = emptySet(), note = "", meetAtPlace = false)
        return start != saved.start || expectedBack != saved.expectedBack || alertAt != saved.alertAt ||
            latestBack != saved.latestBack || departure != saved.departure || groupSize != saved.groupSize ||
            choices.comparable() != saved.choices.comparable()
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
        val outings: List<PlannedOuting> = emptyList(),
        /** The outing shown, 0 until it is saved. */
        val outingId: Long = 0,
        val memo: String? = null,
        val settings: OutingSettings = OutingSettings.DEFAULT,
        val personalPace: PersonalPace? = null,
        val places: List<Place> = emptyList(),
        val drive: DriveState? = null,
        val gearList: GearListSettings = GearListSettings(),
        val messageSections: Map<MessageAudience, Set<MessageSection>> = MessageAudience.entries.associateWith { it.defaultSections },
    )

    companion object {
        /** When the sheet does not give every duration, the relative is told a full day. */
        val UNKNOWN_DURATION_ASSUMPTION: Duration = Duration.ofHours(8)

        const val MAX_NOTE_LENGTH = 500

        /** Today at the next quarter hour in the morning, else tomorrow at nine. */
        internal fun defaultChoices(now: LocalDateTime): OutingChoices = if (now.hour < LATEST_SAME_DAY_START_HOUR) {
            val minutes = now.minute
            val next = now.withSecond(0).withNano(0).plusMinutes(((15 - minutes % 15) % 15).toLong())
            OutingChoices(date = next.toLocalDate(), anchorTime = next.toLocalTime())
        } else {
            OutingChoices(date = now.toLocalDate().plusDays(1), anchorTime = LocalTime.of(DEFAULT_HOUR, 0))
        }

        private const val LATEST_SAME_DAY_START_HOUR = 13
        private const val DEFAULT_HOUR = 9
    }
}
