package fr.descentecanyon.app.ui.outing

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.DaylightCheck
import fr.descentecanyon.app.domain.model.DriveTime
import fr.descentecanyon.app.domain.model.DriveTimeSource
import fr.descentecanyon.app.domain.model.GearGroup
import fr.descentecanyon.app.domain.model.GearItem
import fr.descentecanyon.app.domain.model.GearKey
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.GroupLevel
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.LogbookSource
import fr.descentecanyon.app.domain.model.MessageAudience
import fr.descentecanyon.app.domain.model.MessageSection
import fr.descentecanyon.app.domain.model.OutingAnchor
import fr.descentecanyon.app.domain.model.OutingChoices
import fr.descentecanyon.app.domain.model.OutingPhase
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.model.Stay
import fr.descentecanyon.app.domain.repository.CanyonNotesRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitPredictionRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.domain.repository.WeatherRepository
import fr.descentecanyon.app.domain.usecase.EndOutingUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonDebitPredictionsUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonPreviewUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonWeatherUseCase
import fr.descentecanyon.app.domain.usecase.GetDriveTimeUseCase
import fr.descentecanyon.app.domain.usecase.GetPersonalPaceUseCase
import fr.descentecanyon.app.testutil.FakeDriveTimeRepository
import fr.descentecanyon.app.testutil.FakeOutingPreferencesRepository
import fr.descentecanyon.app.testutil.FakeOutingRepository
import fr.descentecanyon.app.testutil.FakePlaceRepository
import fr.descentecanyon.app.testutil.FakeStayRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutingPlanTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val zone = ZoneId.of("Europe/Paris")
    private val clock = Clock.fixed(LocalDateTime.of(2026, 7, 14, 8, 5).atZone(zone).toInstant(), zone)
    private val outingRepository = FakeOutingRepository()
    private val placeRepository = FakePlaceRepository()
    private val stayRepository = FakeStayRepository(outings = outingRepository)
    private val driveTimeRepository = FakeDriveTimeRepository(route = DriveTime(40, DriveTimeSource.ROUTE, 31.0))
    private val preferences = FakeOutingPreferencesRepository()
    private val logged = mutableListOf<LogbookEntry>()
    private val logbookRepository = mockk<LogbookRepository> {
        coEvery { record(any()) } answers {
            logged += firstArg<LogbookEntry>()
            true
        }
        every { observeAll() } returns flowOf(emptyList())
    }
    private val memo = MutableStateFlow<String?>(null)
    private val canyonNotesRepository = mockk<CanyonNotesRepository> { every { observeNote(42) } returns memo }

    private fun at(hour: Int, minute: Int): LocalDateTime = LocalDateTime.of(2026, 7, 14, hour, minute)

    @Test
    fun `default time is the next quarter hour in the morning, else tomorrow at nine`() {
        assertEquals(
            OutingChoices(LocalDate.of(2026, 7, 14), LocalTime.of(8, 15)),
            OutingPlanViewModel.defaultChoices(LocalDateTime.of(2026, 7, 14, 8, 5)),
        )
        assertEquals(
            OutingChoices(LocalDate.of(2026, 7, 15), LocalTime.of(9, 0)),
            OutingPlanViewModel.defaultChoices(LocalDateTime.of(2026, 7, 14, 15, 40)),
        )
    }

    @Test
    fun `the plan stretches the sheet with the time at the parking and an offline sunset, kept at the first change`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val schedule = state.schedule!!
        // Arrival at 8:15, 20 min to gear up, then 30 min / 2 h / 1 h on the sheet.
        assertEquals(at(8, 35), schedule.walkStart)
        assertEquals(at(12, 5), state.expectedBack)
        assertEquals(at(12, 58), state.latestBack)
        assertEquals(at(14, 58), state.alertAt)
        assertTrue(state.daylight is DaylightCheck.Ok)
        assertEquals(listOf("112", "15", "18", "17"), state.emergencyNumbers.map { it.number })
        // Only looked at: nothing is kept.
        assertEquals(OutingStage.NEW, state.stage)
        assertEquals(emptyList<Any>(), outingRepository.outings.value)

        viewModel.selectAlertMargin(3)
        viewModel.selectGroupSize(6)
        advanceUntilIdle()
        val draft = outingRepository.outings.value.single()
        assertFalse(draft.tracked)
        assertEquals(6, draft.groupSize)
        assertEquals(Duration.ofHours(3), Duration.between(draft.latestBack, draft.alertAt))
        assertEquals(OutingStage.DRAFT, viewModel.uiState.value.stage)

        viewModel.activateTracking()
        advanceUntilIdle()
        val outing = outingRepository.outings.value.single()
        assertTrue(outing.tracked)
        assertEquals(draft.id, outing.id)
        assertEquals(outing, viewModel.uiState.value.savedOuting)
        // Leaving within two hours: the outing is under way.
        assertEquals(OutingStage.UNDERWAY, viewModel.uiState.value.stage)

        viewModel.markBack()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.savedOuting)
        assertEquals(OutingStage.NEW, viewModel.uiState.value.stage)
        assertEquals(emptyList<Any>(), outingRepository.outings.value)
        assertEquals(LogbookSource.OUTING, logged.single().source)
    }

    @Test
    fun `from a starting place the time set is the departure, and the drive is estimated then routed`() = runTest {
        placeRepository.places.value = listOf(Place("Camping", 44.0, 6.8, lastUsedAt = 5, id = 7))
        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(7L, state.choices.placeId)
        assertEquals(OutingAnchor.LEAVE_PLACE, state.choices.anchor)
        assertEquals(DriveTime(40, DriveTimeSource.ROUTE, 31.0), state.drive)
        assertEquals(at(8, 15), state.schedule?.leavePlace)
        assertEquals(at(8, 55), state.schedule?.arriveParking)

        // The user knows the road better: their time stays for this place and canyon.
        viewModel.setManualDrive(25)
        viewModel.selectAnchorTime(LocalTime.of(8, 30))
        advanceUntilIdle()
        assertEquals(DriveTime(25, DriveTimeSource.MANUAL), viewModel.uiState.value.drive)
        assertEquals(at(8, 55), viewModel.uiState.value.schedule?.arriveParking)

        viewModel.activateTracking()
        advanceUntilIdle()
        assertEquals(at(8, 30).atZone(zone).toInstant(), outingRepository.outings.value.single().departure)
        assertEquals(6L, placeRepository.places.value.single().lastUsedAt)

        // Without a place, the time set is the arrival at the parking.
        viewModel.selectPlace(null)
        advanceUntilIdle()
        assertEquals(OutingAnchor.ARRIVE_PARKING, viewModel.uiState.value.choices.anchor)
        assertNull(viewModel.uiState.value.schedule?.leavePlace)
        assertNull(outingRepository.outings.value.single().departure)
    }

    @Test
    fun `every change is kept at once, the follow-up stays on, and a kept outing reopens as it was`() = runTest {
        val first = viewModel()
        advanceUntilIdle()
        first.selectGroupLevel(GroupLevel.WITH_BEGINNERS)
        first.adjustPhase(OutingPhase.DESCENT, 30)
        advanceUntilIdle()
        val id = outingRepository.outings.value.single().id

        val reopened = viewModel(outingId = id)
        advanceUntilIdle()
        assertEquals(first.uiState.value.choices, reopened.uiState.value.choices)
        assertEquals(OutingStage.DRAFT, reopened.uiState.value.stage)

        reopened.toggleGear("gear:HELMET")
        reopened.activateTracking()
        // Changes right after turning the follow-up on keep it on.
        reopened.selectGroupSize(8)
        advanceUntilIdle()
        val updated = outingRepository.outings.value.single()
        assertEquals(id, updated.id)
        assertTrue(updated.tracked)
        assertEquals(8, updated.groupSize)
        assertEquals(setOf("gear:HELMET"), updated.choices?.checkedGear)

        reopened.deleteOuting()
        advanceUntilIdle()
        assertEquals(emptyList<Any>(), outingRepository.outings.value)
        assertEquals(OutingStage.NEW, reopened.uiState.value.stage)
    }

    @Test
    fun `sending the plan to a relative turns the follow-up on, and the relative is told again when the alert moves`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.markRelativeInformed()
        advanceUntilIdle()
        val saved = outingRepository.outings.value.single()
        assertTrue(saved.tracked)
        assertEquals(saved.alertAt, saved.informedAlertAt)

        // Pushed back during the outing: the relative is sent the new time.
        viewModel.delayAlert(60)
        advanceUntilIdle()
        val delayed = outingRepository.outings.value.single()
        assertEquals(saved.alertAt.plus(Duration.ofHours(1)), delayed.alertAt)
        assertEquals(delayed.alertAt, delayed.informedAlertAt)
        assertFalse(viewModel.uiState.value.alertChangedSinceInformed)

        // A new arrival time moves the alert: the relative has to be told again.
        viewModel.selectAnchorTime(LocalTime.of(9, 0))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.alertChangedSinceInformed)
        assertEquals(delayed.informedAlertAt, outingRepository.outings.value.single().informedAlertAt)
    }

    @Test
    fun `a canyon chosen for a day of a stay is kept at once as a draft of the stay, with the routed drive`() = runTest {
        placeRepository.places.value = listOf(Place("Camping", 44.0, 6.8, id = 7))
        stayRepository.stays.value = listOf(Stay(7, LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 16), id = 3))
        val viewModel = viewModel(date = LocalDate.of(2026, 7, 15), stayId = 3)
        advanceUntilIdle()

        val draft = outingRepository.outings.value.single()
        assertFalse(draft.tracked)
        assertEquals(3L, draft.stayId)
        assertEquals(LocalDate.of(2026, 7, 15), draft.choices?.date)
        assertEquals(7L, draft.choices?.placeId)
        // Leaving the campsite at nine, 40 min of road (the route found after the estimate) and 20 min to gear up.
        assertEquals(LocalDateTime.of(2026, 7, 15, 10, 0).atZone(zone).toInstant(), draft.start)
        assertEquals(stayRepository.stays.value.single(), viewModel.uiState.value.stay)

        // Moved out of the stay, it no longer belongs to it.
        viewModel.selectDate(LocalDate.of(2026, 7, 20))
        advanceUntilIdle()
        assertNull(outingRepository.outings.value.single().stayId)
    }

    @Test
    fun `I'm leaving now turns the follow-up on with the time of now`() = runTest {
        val viewModel = viewModel(startNow = true)
        advanceUntilIdle()

        val outing = outingRepository.outings.value.single()
        assertTrue(outing.tracked)
        assertEquals(LocalDate.of(2026, 7, 14), outing.choices?.date)
        assertEquals(LocalTime.of(8, 5), outing.choices?.anchorTime)
        assertTrue(viewModel.uiState.value.startedNow)
        assertEquals(OutingStage.UNDERWAY, viewModel.uiState.value.stage)

        viewModel.startedNowShown()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.startedNow)
    }

    @Test
    fun `the gear list follows the user's changes and the outing`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.gear.any { it.id == "hint:ROPE" })

        viewModel.hideGear(GearKey.KNIFE)
        viewModel.addCustomGear(GearGroup.SHARED, "  Kit spit ")
        advanceUntilIdle()
        val gear = viewModel.uiState.value.gear
        assertFalse(gear.any { it.id == "gear:KNIFE" })
        assertEquals("Kit spit", (gear.last { it.group == GearGroup.SHARED } as GearItem.Custom).label)
        assertEquals(setOf(GearKey.KNIFE), preferences.gear.value.hidden)
    }

    @Test
    fun `the memo of the canyon is read again while preparing`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.memo)

        memo.value = "Parking après le pont, 2x40 m"
        advanceUntilIdle()
        assertEquals("Parking après le pont, 2x40 m", viewModel.uiState.value.memo)
    }

    @Test
    fun `each message carries the sections of its audience`() {
        val data = OutingMessageData(
            canyonName = "Riolan", cotation = "v4a4III", place = "Sigale", day = "Mardi", startingPlace = "Camping", leavePlace = "08:00",
            backAtPlace = "16:00", meetingTime = "08:35",
            meetingParking = GeoPoint(
                canyonId = 42,
                type = GeoPointType.PARKING_AVAL,
                latitude = 43.8,
                longitude = 6.9,
            ),
            walkStart = "09:30", descentStart = "10:00", back = "14:40", backEarliest = "14:10", backLatest = "16:00",
            approachDuration = "30 min", inCanyonDuration = "4 h 30", groupSize = 6, alertTime = "18:00", emergencyNumber = "112",
            downstreamParking = GeoPoint(canyonId = 42, type = GeoPointType.PARKING_AVAL, latitude = 43.8, longitude = 6.9), shuttleKm = 5,
            sheetApproach = "30 min", sheetDescent = "3 h", sheetBack = "10 min", cascadeMax = 25, rope = 60, forecast = "Ensoleillé",
            sharedGear = listOf(
                "Corde 60 m",
                "Kit",
            ),
            guestsBring = listOf("Maillot"), guestsProvided = listOf("Casque"), note = "Covoiturage",
            sheetUrl = "https://example.test/42",
        )
        val coordinates = "43.80000, 6.90000 (https://www.openstreetmap.org/?mlat=43.80000&mlon=6.90000#map=15/43.80000/6.90000)"

        assertEquals(
            listOf(
                "H Riolan v4a4III",
                "P Sigale",
                "DAY Mardi [LEAVE Camping 08:00, WALK 09:30, BACK 14:40, BACKPLACE Camping 16:00]",
                "GROUP 6",
                "SAFE 18:00 112",
                "DOWN $coordinates",
                "SHUTTLE 5",
                "Covoiturage",
                "SHEET https://example.test/42",
            ),
            buildOutingMessage(TestStrings, data, MessageAudience.RELATIVE, MessageAudience.RELATIVE.defaultSections).lines(),
        )
        assertEquals(
            listOf(
                "HT Riolan v4a4III Mardi",
                "MEET 08:35 $coordinates",
                "PROG [WALK 09:30, DESCENT 10:00, RANGE 14:40 14:10 16:00]",
                "DOWN $coordinates",
                "SHUTTLE 5",
                "CANYON 30 min 3 h 10 min",
                "FIG 25 60",
                "WEATHER Ensoleillé",
                "SHARED Corde 60 m, Kit",
                "Covoiturage",
                "SHEET https://example.test/42",
            ),
            buildOutingMessage(TestStrings, data, MessageAudience.TEAM, MessageAudience.TEAM.defaultSections).lines(),
        )
        assertEquals(
            listOf(
                "HG Riolan Mardi",
                "PLACE 08:00 Camping",
                "PROG [APPROACH 30 min, CANYON 4 h 30, BACK 14:40]",
                "WEATHER Ensoleillé",
                "CONFIRM",
                "BRING Maillot",
                "PROVIDED Casque",
                "Covoiturage",
            ),
            buildOutingMessage(
                TestStrings,
                data.copy(meetAtPlace = true),
                MessageAudience.GUESTS,
                MessageAudience.GUESTS.defaultSections,
            ).lines(),
        )
        assertEquals(
            listOf("H Riolan v4a4III", "P Sigale", "SAFE 18:00 112", "Covoiturage"),
            buildOutingMessage(TestStrings, data, MessageAudience.RELATIVE, setOf(MessageSection.SAFETY)).lines(),
        )
    }

    private object TestStrings : OutingMessageStrings {
        override fun headerRelative(name: String, cotation: String) = "H $name $cotation"
        override fun headerTeam(name: String, cotation: String, day: String) = "HT $name $cotation $day"
        override fun headerGuests(name: String, day: String) = "HG $name $day"
        override fun place(place: String) = "P $place"
        override fun meetingAtParking(time: String, coordinates: String) = "MEET $time $coordinates"
        override fun meetingAtParkingUnknown(time: String) = "MEET $time"
        override fun meetingAtPlace(time: String, place: String) = "PLACE $time $place"
        override fun scheduleDay(day: String, steps: String) = "DAY $day [$steps]"
        override fun schedule(steps: String) = "PROG [$steps]"
        override fun stepLeavePlace(place: String, time: String) = "LEAVE $place $time"
        override fun stepWalk(time: String) = "WALK $time"
        override fun stepDescent(time: String) = "DESCENT $time"
        override fun stepBack(time: String) = "BACK $time"
        override fun stepBackRange(time: String, earliest: String, latest: String) = "RANGE $time $earliest $latest"
        override fun stepBackAtPlace(place: String, time: String) = "BACKPLACE $place $time"
        override fun stepApproach(duration: String) = "APPROACH $duration"
        override fun stepInCanyon(duration: String) = "CANYON $duration"
        override fun group(size: Int) = "GROUP $size"
        override fun safetyRelative(alertTime: String, number: String) = "SAFE $alertTime $number"
        override fun safetyOthers(alertTime: String, number: String) = "SAFE-O $alertTime $number"
        override fun downstreamParking(coordinates: String) = "DOWN $coordinates"
        override fun upstreamParking(coordinates: String) = "UP $coordinates"
        override fun shuttle(km: Int) = "SHUTTLE $km"
        override fun canyon(approach: String, descent: String, back: String) = "CANYON $approach $descent $back"
        override fun canyonFigures(cascadeMax: Int?, rope: Int?) = "FIG $cascadeMax $rope"
        override fun weather(forecast: String) = "WEATHER $forecast"
        override fun storm() = "STORM"
        override fun flowEstimate(level: String) = "FLOW $level"
        override fun lastReport(level: String, day: String) = "REPORT $level $day"
        override fun confirmTheDayBefore() = "CONFIRM"
        override fun sharedGear(items: String) = "SHARED $items"
        override fun guestsBring(items: String) = "BRING $items"
        override fun guestsProvided(items: String) = "PROVIDED $items"
        override fun sheet(url: String) = "SHEET $url"
        override fun back(name: String, time: String) = "BACKMSG $name $time"
        override fun delay(name: String, alertTime: String, number: String) = "DELAY $name $alertTime $number"
    }

    private fun viewModel(outingId: Long = 0, date: LocalDate? = null, stayId: Long = 0, startNow: Boolean = false): OutingPlanViewModel {
        val canyonRepository = mockk<CanyonRepository> {
            coEvery { getCanyonPreview(42) } returns Result.success(
                CanyonDetail(
                    canyon = Canyon(
                        id = 42, nom = "Riolan", nomComplet = "Riolan", pays = "France", commune = "Sigale", cotation = "v4a4III",
                        url = "https://example.test/42", tempsApproche = "30min", tempsDescente = "2h", tempsRetour = "1h", cordeMin = 60,
                    ),
                    geoPoints = listOf(GeoPoint(canyonId = 42, type = GeoPointType.PARKING_AMONT, latitude = 43.95, longitude = 6.9)),
                ),
            )
        }
        val weather = mockk<WeatherRepository> { coEvery { getCachedCanyonWeather(42) } returns null }
        val predictions = mockk<DebitPredictionRepository> { coEvery { getCachedPredictions(42) } returns null }
        val debits = mockk<DebitRepository> { every { observeLatestKnownDebits(setOf(42)) } returns flowOf(emptyMap()) }
        val connectivity = mockk<ConnectivityObserver> { every { isCurrentlyOnline() } returns false }
        val online = mockk<ConnectivityObserver> { every { isCurrentlyOnline() } returns true }
        return OutingPlanViewModel(
            savedStateHandle = SavedStateHandle(
                listOfNotNull(
                    "canyonId" to CANYON_ID,
                    "outingId" to outingId,
                    "stayId" to stayId,
                    "startNow" to startNow,
                    date?.let { "date" to it.toString() },
                ).toMap(),
            ),
            getCanyonPreviewUseCase = GetCanyonPreviewUseCase(canyonRepository),
            getCanyonWeatherUseCase = GetCanyonWeatherUseCase(weather),
            getCanyonDebitPredictionsUseCase = GetCanyonDebitPredictionsUseCase(predictions),
            debitRepository = debits,
            outingRepository = outingRepository,
            canyonNotesRepository = canyonNotesRepository,
            endOutingUseCase = EndOutingUseCase(outingRepository, logbookRepository, clock),
            placeRepository = placeRepository,
            stayRepository = stayRepository,
            outingPreferencesRepository = preferences,
            getPersonalPaceUseCase = GetPersonalPaceUseCase(logbookRepository, canyonRepository),
            getDriveTimeUseCase = GetDriveTimeUseCase(driveTimeRepository, online),
            driveTimeRepository = driveTimeRepository,
            connectivityObserver = connectivity,
            clock = clock,
        )
    }

    private companion object {
        /** An Int: in a map with the Long outing id, a bare 42 would be read as a Long. */
        const val CANYON_ID: Int = 42
    }
}
