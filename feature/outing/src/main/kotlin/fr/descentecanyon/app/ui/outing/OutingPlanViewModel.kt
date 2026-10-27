package fr.descentecanyon.app.ui.outing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonDuration
import fr.descentecanyon.app.domain.model.CanyonLink
import fr.descentecanyon.app.domain.model.CanyonSearchItem
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
import fr.descentecanyon.app.domain.model.NextCanyon
import fr.descentecanyon.app.domain.model.NextCanyonInput
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
import fr.descentecanyon.app.domain.model.Stay
import fr.descentecanyon.app.domain.model.SunCalculator
import fr.descentecanyon.app.domain.model.agenda
import fr.descentecanyon.app.domain.model.defaultFor
import fr.descentecanyon.app.domain.model.gearChecklist
import fr.descentecanyon.app.domain.model.haversineKm
import fr.descentecanyon.app.domain.model.meetingParking
import fr.descentecanyon.app.domain.model.normalizeForSearch
import fr.descentecanyon.app.domain.model.placeOnNewDate
import fr.descentecanyon.app.domain.repository.CanyonNotesRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.DriveTimeRepository
import fr.descentecanyon.app.domain.repository.OutingPreferencesRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.PlaceRepository
import fr.descentecanyon.app.domain.repository.StayRepository
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
import java.time.temporal.ChronoUnit
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The drive time known for a place and a parking. */
data class DriveState(val placeId: Long, val destination: GeoPoint, val time: DriveTime)

/** A canyon after the first one of the outing, with what the plan knows of it. */
data class NextCanyonState(
    val canyon: NextCanyon,
    /** Null while loading, or when the canyon is no longer in the catalog. */
    val detail: CanyonDetail? = null,
    /** By car: the drive from the parking of the canyon before, estimated. */
    val drive: DriveTime? = null,
)

/** A canyon that can follow the last one of the outing; [distanceKm] in a straight line from it. */
data class CanyonCandidate(val id: Int, val name: String, val cotation: String, val distanceKm: Double?)

/** The canyons offered to follow the last one: those around it, or those matching the name typed. */
data class CanyonPickerState(val query: String = "", val candidates: List<CanyonCandidate> = emptyList(), val isLoading: Boolean = true)

/** Where the outing on screen stands, which decides the main action of the screen. */
enum class OutingStage {
    /** Not kept yet: the first change keeps it as a draft. */
    NEW,

    /** Kept, follow-up off. */
    DRAFT,

    /** Followed: notifications from the departure, reminder at the return. */
    TRACKED,

    /** Followed and under way. */
    UNDERWAY,
}

data class OutingPlanUiState(
    val canyon: CanyonDetail? = null,
    val isLoading: Boolean = true,
    val choices: OutingChoices,
    val today: LocalDate,
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
    /** The outing as kept, draft or followed. */
    val savedOuting: PlannedOuting? = null,
    val stage: OutingStage = OutingStage.NEW,
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
    /** "I'm leaving now" just turned the follow-up on: the screen asks for notifications and offers to tell a relative. */
    val startedNow: Boolean = false,
    /** The canyons after the first one, in their order. */
    val nextCanyons: List<NextCanyonState> = emptyList(),
) {
    /** "Pissarde (partie amont) + Pissarde (partie aval)". */
    val title: String?
        get() = canyon?.canyon?.nom?.let { first -> (listOf(first) + nextCanyons.map { it.canyon.name }).joinToString(" + ") }

    /** Another canyon can follow the last one. */
    val canAddCanyon: Boolean get() = nextCanyons.size + 1 < OutingChoices.MAX_CANYONS

    val withBeginners: Boolean get() = choices.groupLevel != GroupLevel.AUTONOMOUS
}

/**
 * Plans an outing from the durations of the sheet stretched for the group: the whole day from the
 * starting place, daylight left, conditions of the day, gear, messages for a relative, the team or
 * beginners, and the follow-up of the outing. Every change is kept at once, as a draft until the
 * user turns the follow-up on. Works offline from the catalog, the last forecast downloaded, a
 * sunset computed on the device and an estimated drive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OutingPlanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getCanyonPreviewUseCase: GetCanyonPreviewUseCase,
    private val canyonRepository: CanyonRepository,
    private val getCanyonWeatherUseCase: GetCanyonWeatherUseCase,
    private val getCanyonDebitPredictionsUseCase: GetCanyonDebitPredictionsUseCase,
    private val debitRepository: DebitRepository,
    private val outingRepository: OutingRepository,
    private val canyonNotesRepository: CanyonNotesRepository,
    private val endOutingUseCase: EndOutingUseCase,
    private val placeRepository: PlaceRepository,
    private val stayRepository: StayRepository,
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
    private val requestedStayId: Long = savedStateHandle["stayId"] ?: 0L
    private val startNow: Boolean = savedStateHandle["startNow"] ?: false

    private val choices = MutableStateFlow<OutingChoices?>(null)
    private val sources = MutableStateFlow(Sources())
    private val driveRefresh = MutableStateFlow(0)

    /** One write at a time: a change made during a write sees the id of the outing it created. */
    private val saving = Mutex()

    val uiState: StateFlow<OutingPlanUiState> = combine(choices, sources, ::buildState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, buildState(choices.value, sources.value))

    /** Null while no canyon is being added: the catalog is read only then. */
    private val pickerQuery = MutableStateFlow<String?>(null)

    val canyonPicker: StateFlow<CanyonPickerState> = pickerQuery
        .flatMapLatest { query ->
            if (query == null) {
                flowOf(CanyonPickerState(isLoading = false))
            } else {
                canyonRepository.observeSearchCatalog().map { catalog ->
                    CanyonPickerState(query, candidates(catalog, query), isLoading = false)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CanyonPickerState())

    init {
        load()
        viewModelScope.launch {
            outingRepository.observeAll().collect { outings ->
                sources.update { it.copy(outings = outings) }
                forgetIfRemoved(outings)
            }
        }
        viewModelScope.launch {
            outingRepository.observeAll().agenda(clock).collect { agenda -> sources.update { it.copy(underwayId = agenda.underway?.id) } }
        }
        viewModelScope.launch { canyonNotesRepository.observeNote(canyonId).collect { memo -> sources.update { it.copy(memo = memo) } } }
        viewModelScope.launch {
            debitRepository.observeLatestKnownDebits(setOf(canyonId)).collect { debits ->
                sources.update { it.copy(lastReport = debits[canyonId]) }
            }
        }
        viewModelScope.launch { placeRepository.observeAll().collect { places -> sources.update { it.copy(places = places) } } }
        viewModelScope.launch { stayRepository.observeAll().collect { stays -> sources.update { it.copy(stays = stays) } } }
        viewModelScope.launch { outingPreferencesRepository.observeSettings().collect { s -> sources.update { it.copy(settings = s) } } }
        viewModelScope.launch { outingPreferencesRepository.observeGearList().collect { g -> sources.update { it.copy(gearList = g) } } }
        viewModelScope.launch {
            outingPreferencesRepository.observeMessageSections().collect { s -> sources.update { it.copy(messageSections = s) } }
        }
        viewModelScope.launch { getPersonalPaceUseCase().let { pace -> sources.update { it.copy(personalPace = pace) } } }
        viewModelScope.launch { initChoices() }
        viewModelScope.launch { followDrive() }
        viewModelScope.launch { loadNextCanyons() }
        viewModelScope.launch { followComputedTimes() }
    }

    /** The starting place follows the day while it is the default one: the campsite on the days of a stay, home after. */
    fun selectDate(date: LocalDate) = updateChoices { choices ->
        val current = sources.value
        val placeId = current.places.placeOnNewDate(choices.placeId, choices.date, date, current.stays)
        if (placeId == choices.placeId) choices.copy(date = date) else choices.copy(date = date).withPlace(placeId)
    }

    /** The time set is leaving the place when there is one, else arriving at the parking (an outing of 2.7 may differ). */
    fun selectAnchorTime(time: LocalTime) = updateChoices { it.copy(anchorTime = time).withPlace(it.placeId) }

    /** Null: no starting place, the time set is the arrival at the parking. */
    fun selectPlace(placeId: Long?) = updateChoices { it.withPlace(placeId) }

    fun selectGroupSize(size: Int) = updateChoices { it.copy(groupSize = size.coerceIn(OutingSettings.GROUP_SIZE_RANGE)) }

    fun selectGroupLevel(level: GroupLevel) = updateChoices { it.copy(groupLevel = level) }

    fun selectShuttle(withShuttle: Boolean) = updateChoices { it.copy(withShuttle = withShuttle) }

    fun selectAlertMargin(hours: Int) = updateChoices { it.copy(alertMarginHours = hours) }

    /** Lengthens or shortens a step of the [canyon]-th canyon (0: the first) for this outing; 0 minutes brings back the computed time. */
    fun adjustPhase(canyon: Int, phase: OutingPhase, minutes: Int) = updateChoices { choices ->
        fun Map<OutingPhase, Int>.adjusted() = if (minutes == 0) this - phase else this + (phase to minutes)
        if (canyon == 0) {
            choices.copy(adjustments = choices.adjustments.adjusted())
        } else {
            choices.withNextCanyon(canyon - 1) { it.copy(adjustments = it.adjustments.adjusted()) }
        }
    }

    /** Looks for a canyon to follow the last one: around it while [query] is blank, else by name. */
    fun searchCanyons(query: String) {
        pickerQuery.value = query
    }

    fun closeCanyonPicker() {
        pickerQuery.value = null
    }

    /** The canyon comes after the last one, on foot when it is close, else by car. */
    fun addCanyon(candidate: CanyonCandidate) {
        pickerQuery.value = null
        updateChoices { choices ->
            val ids = listOf(canyonId) + choices.nextCanyons.map { it.canyonId }
            if (candidate.id in ids || ids.size >= OutingChoices.MAX_CANYONS) return@updateChoices choices
            val link = if ((candidate.distanceKm ?: 0.0) > ON_FOOT_MAX_KM) CanyonLink.BY_CAR else CanyonLink.ON_FOOT
            choices.copy(nextCanyons = choices.nextCanyons + NextCanyon(candidate.id, candidate.name, link))
        }
    }

    /** [index] counts the canyons after the first one. */
    fun removeCanyon(index: Int) = updateChoices { choices ->
        choices.copy(nextCanyons = choices.nextCanyons.filterIndexed { i, _ -> i != index })
    }

    fun selectLink(index: Int, link: CanyonLink) = updateChoices { choices -> choices.withNextCanyon(index) { it.copy(link = link) } }

    private fun OutingChoices.withNextCanyon(index: Int, change: (NextCanyon) -> NextCanyon) =
        copy(nextCanyons = nextCanyons.mapIndexed { i, canyon -> if (i == index) change(canyon) else canyon })

    /** Around the last canyon of the outing, nearest first, or matching [query]; the canyons of the outing left out. */
    private fun candidates(catalog: List<CanyonSearchItem>, query: String): List<CanyonCandidate> {
        val ids = listOf(canyonId) + choices.value?.nextCanyons.orEmpty().map { it.canyonId }
        val origin = catalog.firstOrNull { it.id == ids.last() }
        val text = query.normalizeForSearch()
        return catalog.asSequence()
            .filter { it.id !in ids }
            .filter { text.isEmpty() || it.normalizedNom.contains(text) || it.normalizedNomComplet.contains(text) }
            .map { item -> CanyonCandidate(item.id, item.nom, item.cotation, origin?.distanceKmTo(item)) }
            .filter { text.isNotEmpty() || (it.distanceKm != null && it.distanceKm <= NEARBY_KM) }
            .sortedWith(compareBy(nullsLast()) { it.distanceKm })
            .take(MAX_CANDIDATES)
            .toList()
    }

    private fun CanyonSearchItem.distanceKmTo(other: CanyonSearchItem): Double? {
        val latitude = representativeLat ?: return null
        val longitude = representativeLng ?: return null
        val otherLatitude = other.representativeLat ?: return null
        val otherLongitude = other.representativeLng ?: return null
        return haversineKm(latitude, longitude, otherLatitude, otherLongitude)
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

    /** Checked items stay with the outing at once: packing happens in several goes. */
    fun toggleGear(id: String) = updateChoices { choices ->
        choices.copy(checkedGear = if (id in choices.checkedGear) choices.checkedGear - id else choices.checkedGear + id)
    }

    fun setNote(note: String) = updateChoices { it.copy(note = note.take(MAX_NOTE_LENGTH)) }

    fun setMeetAtPlace(meetAtPlace: Boolean) = updateChoices { it.copy(meetAtPlace = meetAtPlace) }

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

    /** Turns the follow-up on: notifications during the outing, reminder at the return. */
    fun activateTracking() = persist { it.copy(tracked = true) }

    /** The plan went to a relative who counts on its alert time: the outing is followed as the relative does. */
    fun markRelativeInformed() = persist { it.copy(tracked = true, informedAlertAt = it.alertAt) }

    /** The alert comes [minutes] later: the outing takes longer than planned; a relative informed is sent the new time. */
    fun delayAlert(minutes: Int) {
        val informed = sources.value.saved?.relativeInformed == true
        choices.update { it?.copy(alertDelayMinutes = it.alertDelayMinutes + minutes) }
        persist { if (informed) it.copy(informedAlertAt = it.alertAt) else it }
    }

    /** "I'm leaving now" was shown its follow-up: the screen asked for notifications. */
    fun startedNowShown() = sources.update { it.copy(startedNow = false) }

    /** Back from the outing: it goes to the logbook and its notifications are cancelled; the plan stays on screen. */
    fun markBack() = forget { id -> endOutingUseCase(id) }

    /** The outing will not happen: it is forgotten, the plan stays on screen. */
    fun deleteOuting() = forget { id -> outingRepository.delete(id) }

    private fun forget(remove: suspend (Long) -> Unit) {
        viewModelScope.launch {
            saving.withLock {
                val id = sources.value.saved?.id ?: return@withLock
                remove(id)
                sources.update { it.copy(saved = null) }
                choices.update { it?.copy(alertDelayMinutes = 0, checkedGear = emptySet()) }
            }
        }
    }

    /**
     * Keeps the outing on screen, as a draft the first time, with [change] applied to what was kept
     * (follow-up, relative informed). A change of the plan moves the times of a followed outing, so
     * its notifications follow.
     */
    private fun persist(change: (PlannedOuting) -> PlannedOuting = { it }) {
        viewModelScope.launch {
            saving.withLock {
                val current = sources.value
                // From the sources: the derived state may not reflect a choice made just before.
                val state = buildState(choices.value, current)
                val planned = state.toPlannedOuting() ?: return@withLock
                val saved = current.saved
                val stayId = (saved?.stayId ?: requestedStayId.takeIf { it > 0 })
                    ?.takeIf { id -> current.stays.firstOrNull { it.id == id }?.covers(state.choices.date) != false }
                val outing = change(
                    planned.copy(
                        id = saved?.id ?: 0,
                        informedAlertAt = saved?.informedAlertAt,
                        tracked = saved?.tracked ?: false,
                        stayId = stayId,
                    ),
                )
                if (outing == saved) return@withLock
                val id = outingRepository.save(outing)
                sources.update { it.copy(saved = outing.copy(id = id)) }
                if (outing.tracked && saved?.tracked != true) state.place?.let { placeRepository.markUsed(it.id) }
            }
        }
    }

    /** An outing ended or deleted elsewhere (notification, home screen) leaves the plan on screen, no longer kept. */
    private suspend fun forgetIfRemoved(outings: List<PlannedOuting>) {
        val saved = sources.value.saved ?: return
        if (outings.any { it.id == saved.id }) return
        // A list read before the last write may lack the outing: it is gone only if the database says so.
        if (outingRepository.get(saved.id) == null) sources.update { if (it.saved?.id == saved.id) it.copy(saved = null) else it }
    }

    /** Times that change without the user (a route found, new settings): a kept outing follows them. */
    private suspend fun followComputedTimes() {
        uiState.map { it.toPlannedOuting()?.times() }.distinctUntilChanged().collect { times ->
            val saved = sources.value.saved ?: return@collect
            if (times != null && times != saved.times()) persist()
        }
    }

    private fun PlannedOuting.times() =
        listOf(start, expectedBack, latestBack, alertAt, departure, groupSize, choices?.nextCanyons?.map { it.from })

    private fun updateGearList(change: (GearListSettings) -> GearListSettings) {
        val updated = change(sources.value.gearList)
        sources.update { it.copy(gearList = updated) }
        viewModelScope.launch { outingPreferencesRepository.setGearList(updated) }
    }

    /** A change of the user is kept at once. */
    private fun updateChoices(change: (OutingChoices) -> OutingChoices) {
        val before = choices.value ?: return
        choices.update { current -> current?.let(change) }
        if (choices.value != before) persist()
    }

    private suspend fun initChoices() {
        val settings = outingPreferencesRepository.observeSettings().first()
        val places = placeRepository.observeAll().first()
        val stays = stayRepository.observeAll().first()
        val outings = outingRepository.observeAll().first()
        val today = LocalDate.now(clock)
        val existing = when {
            requestedOutingId > 0 -> outings.firstOrNull { it.id == requestedOutingId } ?: outingRepository.get(requestedOutingId)
            startNow -> outings.firstOrNull { it.canyonId == canyonId && it.choices?.date == today }
            requestedDate == null -> outings.firstOrNull { it.canyonId == canyonId }
            else -> outings.firstOrNull { it.canyonId == canyonId && it.choices?.date == requestedDate }
        }
        sources.update { it.copy(saved = existing) }
        val kept = existing?.let { it.choices ?: legacyChoices(it) }
        when {
            startNow && existing?.isUnderway(clock.instant()) != true -> {
                // Leaving now: the time set is now, from the place of the day if any.
                val now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES)
                val base = kept ?: newChoices(settings, places, stays)
                choices.value = base.copy(date = today, anchorTime = now.toLocalTime(), alertDelayMinutes = 0)
                awaitPlan()
                persist { it.copy(tracked = true) }
                sources.update { it.copy(startedNow = true) }
            }

            kept != null -> choices.value = kept

            else -> {
                val initial = newChoices(settings, places, stays)
                choices.value = initial
                if (initial.date.isAfter(today) || requestedStayId > 0) {
                    awaitPlan()
                    if (initial.date.isAfter(today)) startEarlierIfLate(initial)
                }
                // A day of a stay was given a canyon: the stay shows it at once.
                if (requestedStayId > 0) persist()
            }
        }
    }

    /**
     * A day ahead starts at nine by default; when the group would then be back after the sunset, it
     * starts at the latest time that keeps it in daylight, not before [EARLIEST_DEFAULT_START]. A
     * time the user already changed stays.
     */
    private fun startEarlierIfLate(initial: OutingChoices) {
        val latest = (uiState.value.daylight as? DaylightCheck.Late)?.latestAnchor ?: return
        val earlier = latest.withMinute(latest.minute - latest.minute % QUARTER_MINUTES).withSecond(0).withNano(0)
        if (earlier >= initial.anchorTime || earlier < EARLIEST_DEFAULT_START) return
        choices.update { if (it == initial) it.copy(anchorTime = earlier) else it }
    }

    /** Until the times can be computed: the canyon loaded and, from a place, a first drive time. */
    private suspend fun awaitPlan() {
        uiState.first { it.schedule != null && (it.place == null || it.meetingParking == null || it.drive != null) }
    }

    private fun newChoices(settings: OutingSettings, places: List<Place>, stays: List<Stay>): OutingChoices {
        val now = LocalDateTime.now(clock)
        val default = defaultChoices(now)
        val date = requestedDate ?: default.date
        val time = if (date == default.date) default.anchorTime else LocalTime.of(DEFAULT_HOUR, 0)
        val stay = stays.firstOrNull { it.id == requestedStayId }
        val place = places.firstOrNull { it.id == (stay?.placeId ?: requestedPlaceId) } ?: places.defaultFor(date, stays)
        return OutingChoices(
            date = date,
            anchorTime = time,
            groupSize = settings.defaultGroupSize,
            groupLevel = settings.defaultGroupLevel,
        ).withPlace(place?.id)
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

    /** The sheets of the canyons added after the first one, each read once. */
    private suspend fun loadNextCanyons() {
        choices.map { it?.nextCanyons.orEmpty().map(NextCanyon::canyonId) }.distinctUntilChanged().collect { ids ->
            ids.filterNot { it in sources.value.nextDetails }.forEach { id ->
                val detail = getCanyonPreviewUseCase(id).getOrNull()
                sources.update { it.copy(nextDetails = it.nextDetails + (id to detail)) }
            }
        }
    }

    private fun buildState(choices: OutingChoices?, sources: Sources): OutingPlanUiState {
        val today = LocalDate.now(clock)
        val settings = sources.settings
        val current = choices ?: defaultChoices(LocalDateTime.now(clock))
        val pace = OutingPace.of(settings, current.groupSize, current.groupLevel, sources.personalPace)
        val detail = sources.canyon
        val canyon = detail?.canyon
        val approach = CanyonDuration.parse(canyon?.tempsApproche)
        val descent = CanyonDuration.parse(canyon?.tempsDescente)
        val back = CanyonDuration.parse(canyon?.tempsRetour)
        val nextCanyons = nextCanyonStates(detail, current.nextCanyons, sources.nextDetails)
        val nextLoaded = current.nextCanyons.all { it.canyonId in sources.nextDetails }
        val lastDetail = nextCanyons.lastOrNull()?.detail ?: detail
        // The shuttle of the day joins the end of the last canyon to the start of the first one.
        val shuttles = (listOf(canyon) + nextCanyons.map { it.detail?.canyon }).mapNotNull { Shuttle.parse(it?.navette) }
        val sheetShuttle = shuttles.takeIf { it.isNotEmpty() }?.let { Shuttle(it.sumOf(Shuttle::distanceKm)) }
        val withShuttle = sheetShuttle != null && current.withShuttle != false
        val place = sources.places.firstOrNull { it.id == current.placeId }
        val meetingParking = if (withShuttle) {
            lastDetail?.geoPoints?.firstOrNull { it.type == GeoPointType.PARKING_AVAL } ?: detail?.meetingParking(true)
        } else {
            detail?.meetingParking(false)
        }
        val drive = sources.drive?.takeIf { it.placeId == place?.id && it.destination == meetingParking }?.time
        val schedule = detail?.takeIf { nextLoaded }?.let {
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
                    nextCanyons = nextCanyons.map { next ->
                        val sheet = next.detail?.canyon
                        NextCanyonInput(
                            link = next.canyon.link,
                            approach = CanyonDuration.parse(sheet?.tempsApproche),
                            descent = CanyonDuration.parse(sheet?.tempsDescente),
                            back = CanyonDuration.parse(sheet?.tempsRetour),
                            driveMinutes = next.drive?.minutes,
                            adjustments = next.canyon.adjustments,
                        )
                    },
                ),
            )
        }
        val dayWeather = sources.weather?.dayOf(current.date)
        val sunset = dayWeather?.sunset ?: detail?.let { computedSunset(it, current.date, sources.weather) }
        val expectedBack = schedule?.let { it.backAtCar?.estimate ?: it.walkStart.plus(UNKNOWN_DURATION_ASSUMPTION) }
        val latestBack = schedule?.let { it.backAtCar?.latest ?: it.walkStart.plus(UNKNOWN_DURATION_ASSUMPTION) }
        val alertAt = latestBack?.plusHours(current.alertMarginHours.toLong())?.plusMinutes(current.alertDelayMinutes.toLong())
        val saved = sources.saved
        val informedAlertAt = saved?.informedAlertAt
        return OutingPlanUiState(
            canyon = detail,
            isLoading = sources.isLoading || choices == null,
            choices = current,
            today = today,
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
            downstreamParking = lastDetail?.geoPoints?.firstOrNull { it.type == GeoPointType.PARKING_AVAL },
            upstreamParking = detail?.geoPoints?.firstOrNull { it.type == GeoPointType.PARKING_AMONT },
            savedOuting = saved,
            stage = when {
                saved == null -> OutingStage.NEW
                !saved.tracked -> OutingStage.DRAFT
                sources.underwayId == saved.id -> OutingStage.UNDERWAY
                else -> OutingStage.TRACKED
            },
            expectedBack = expectedBack,
            latestBack = latestBack,
            alertAt = alertAt,
            memo = sources.memo,
            gearList = sources.gearList,
            gear = gearChecklist(
                sources.gearList,
                GearContext(
                    ropeLengthMeters = (
                        listOf(canyon?.cordeMin) + nextCanyons.map {
                            it.detail?.canyon?.cordeMin
                        }
                        ).filterNotNull().maxOrNull(),
                    shuttleKm = sheetShuttle?.takeIf { withShuttle }?.distanceKm?.roundToInt(),
                    longestOnFootMinutes = schedule?.longestOnFoot?.toMinutes(),
                ),
            ),
            messageSections = sources.messageSections,
            alertChangedSinceInformed = informedAlertAt != null && alertAt != null && informedAlertAt != alertAt.toInstant(),
            startedNow = sources.startedNow,
            nextCanyons = nextCanyons,
        )
    }

    /** The canyons after the first one, with the drive from the end of the one before when they are reached by car. */
    private fun nextCanyonStates(first: CanyonDetail?, next: List<NextCanyon>, details: Map<Int, CanyonDetail?>): List<NextCanyonState> {
        var previous = first
        return next.map { canyon ->
            val detail = details[canyon.canyonId]
            val from = previous?.let {
                it.geoPoints.firstOrNull { point -> point.type == GeoPointType.PARKING_AVAL }
                    ?: it.meetingParking(false)
            }
            val to = detail?.meetingParking(false)
            val drive = if (from != null &&
                to != null
            ) {
                DriveTime.estimate(from.latitude, from.longitude, to.latitude, to.longitude)
            } else {
                null
            }
            previous = detail
            NextCanyonState(canyon, detail, drive)
        }
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
            choices = choices.copy(
                nextCanyons = choices.nextCanyons.mapIndexed { index, next ->
                    val previousEnd = if (index == 0) schedule.descentEnd else schedule.nextCanyons.getOrNull(index - 1)?.descentEnd
                    next.copy(from = previousEnd?.estimate?.toInstant())
                },
            ),
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
        val outings: List<PlannedOuting> = emptyList(),
        /** The outing as this screen last kept it: the base of the next write, whatever the database read says. */
        val saved: PlannedOuting? = null,
        val underwayId: Long? = null,
        val memo: String? = null,
        val settings: OutingSettings = OutingSettings.DEFAULT,
        val personalPace: PersonalPace? = null,
        val places: List<Place> = emptyList(),
        val stays: List<Stay> = emptyList(),
        val drive: DriveState? = null,
        /** The sheets of the canyons after the first one, by id; null when one could not be read. */
        val nextDetails: Map<Int, CanyonDetail?> = emptyMap(),
        val gearList: GearListSettings = GearListSettings(),
        val messageSections: Map<MessageAudience, Set<MessageSection>> = MessageAudience.entries.associateWith { it.defaultSections },
        val startedNow: Boolean = false,
    )

    companion object {
        /** When the sheet does not give every duration, the relative is told a full day. */
        val UNKNOWN_DURATION_ASSUMPTION: Duration = Duration.ofHours(8)

        const val MAX_NOTE_LENGTH = 500

        /** Farther than this in a straight line, the next canyon is reached by car. */
        const val ON_FOOT_MAX_KM = 2.0

        private const val NEARBY_KM = 30.0
        private const val MAX_CANDIDATES = 20
        private const val STOP_TIMEOUT_MS = 5_000L

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
        private const val QUARTER_MINUTES = 15

        /** A default start is not earlier: the group could not leave sooner. */
        private val EARLIEST_DEFAULT_START: LocalTime = LocalTime.of(6, 0)
    }
}
