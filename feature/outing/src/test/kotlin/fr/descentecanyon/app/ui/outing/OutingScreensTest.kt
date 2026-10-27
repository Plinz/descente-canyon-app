package fr.descentecanyon.app.ui.outing

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.DailyWeather
import fr.descentecanyon.app.domain.model.DriveTime
import fr.descentecanyon.app.domain.model.DriveTimeSource
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.GroupLevel
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.OutingChoices
import fr.descentecanyon.app.domain.model.OutingSettings
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.Stay
import fr.descentecanyon.app.domain.model.WeatherLocationSource
import fr.descentecanyon.app.domain.model.WeatherTarget
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.domain.repository.PreferencesRepository
import fr.descentecanyon.app.domain.repository.WeatherRepository
import fr.descentecanyon.app.domain.usecase.GetPersonalPaceUseCase
import fr.descentecanyon.app.domain.usecase.GetPlaceWeatherUseCase
import fr.descentecanyon.app.domain.usecase.SaveStayUseCase
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutingScreensTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val zone = ZoneId.of("Europe/Paris")
    private val now = LocalDateTime.of(2026, 7, 14, 10, 0).atZone(zone).toInstant()
    private val clock = Clock.fixed(now, zone)

    @Test
    fun `a new stay takes a place added on the map and its dates, then opens`() = runTest {
        val places = FakePlaceRepository()
        val stays = FakeStayRepository()
        val viewModel = stayEditViewModel(SavedStateHandle(), stays, places)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(false, viewModel.uiState.value.canSave)
        // A new stay starts today at the earliest.
        assertEquals(LocalDate.of(2026, 7, 14), viewModel.uiState.value.minDate)

        viewModel.addPlace(Place("Camping", 44.0, 6.8))
        advanceUntilIdle()
        // A week from today until the dates are picked.
        assertEquals(LocalDate.of(2026, 7, 20), viewModel.uiState.value.end)
        assertTrue(viewModel.uiState.value.canSave)

        // Six weeks are cut to the longest stay.
        viewModel.selectDates(LocalDate.of(2026, 7, 20), LocalDate.of(2026, 8, 31))
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 8, 19), viewModel.uiState.value.end)

        viewModel.selectDates(LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 18))
        viewModel.save()
        advanceUntilIdle()
        val stay = stays.stays.value.single()
        assertEquals(Stay(places.places.value.single().id, LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 18), stay.id), stay)
        assertEquals(stay.id, viewModel.uiState.value.savedId)
    }

    @Test
    fun `new dates of a stay say which outings they leave out, and those go back among the drafts`() = runTest {
        val places = FakePlaceRepository(listOf(Place("Camping", 44.0, 6.8, id = 7)))
        val outings = FakeOutingRepository()
        val stays = FakeStayRepository(listOf(Stay(7, LocalDate.of(2026, 7, 12), LocalDate.of(2026, 7, 20), id = 3)), outings)
        outings.save(outingOn(LocalDate.of(2026, 7, 15), "Bollinette", stayId = 3).copy(tracked = false))
        outings.save(outingOn(LocalDate.of(2026, 7, 19), "Riolan", stayId = 3).copy(tracked = false))
        val viewModel = stayEditViewModel(SavedStateHandle(mapOf("stayId" to 3L)), stays, places, outings)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        // A stay already begun keeps its first day in the calendar.
        assertEquals(LocalDate.of(2026, 7, 12), viewModel.uiState.value.minDate)
        assertEquals(0, viewModel.uiState.value.outingsLeftOut)

        viewModel.selectDates(LocalDate.of(2026, 7, 12), LocalDate.of(2026, 7, 17))
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.outingsLeftOut)

        viewModel.save()
        advanceUntilIdle()
        assertEquals(listOf("Bollinette" to 3L, "Riolan" to null), outings.outings.value.map { it.canyonName to it.stayId })
        // Out of the stay, the draft is listed with the others again.
        val list = outingsViewModel(outings, stays, places)
        backgroundScope.launch { list.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(listOf("Riolan"), list.uiState.value.drafts.map { it.canyonName })
    }

    @Test
    fun `a stay shows its days still to come with the weather at the place and the outing of each day`() = runTest {
        val place = Place("Camping", 44.0, 6.8, id = 7)
        val stay = Stay(7, LocalDate.of(2026, 7, 12), LocalDate.of(2026, 7, 16), id = 3)
        val outings = FakeOutingRepository()
        outings.save(outingOn(LocalDate.of(2026, 7, 15), "Bollinette", stayId = 3).copy(tracked = false))
        // Planned from the campsite on a day of the stay, before the stay existed.
        outings.save(outingOn(LocalDate.of(2026, 7, 16), "Riolan", placeId = 7))
        outings.save(outingOn(LocalDate.of(2026, 7, 16), "Ailleurs", placeId = 9))
        val forecast = CanyonWeather(
            target = WeatherTarget(44.0, 6.8, WeatherLocationSource.UNKNOWN),
            timezone = "Europe/Paris",
            fetchedAt = now,
            daily = listOf(DailyWeather(LocalDate.of(2026, 7, 15), weatherCode = 95, precipitationSumMm = 26.0)),
        )
        val logbook = listOf(
            LogbookEntry(canyonId = 50, canyonName = "Avant", date = LocalDate.of(2026, 7, 11)),
            LogbookEntry(canyonId = 51, canyonName = "Hier", date = LocalDate.of(2026, 7, 13)),
            LogbookEntry(canyonId = 52, canyonName = "Ce matin", date = LocalDate.of(2026, 7, 14)),
        )
        val viewModel = stayViewModel(stay, place, outings, cached = forecast, logbook = logbook)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val days = state.days
        assertEquals((14..16).map { LocalDate.of(2026, 7, it) }, days.map { it.date })
        assertEquals(listOf(emptyList(), listOf("Bollinette"), listOf("Riolan")), days.map { day -> day.outings.map { it.canyonName } })
        assertEquals(26.0, days[1].weather?.precipitationSumMm)
        assertNull(days[0].weather)
        // The forecast covers these days: none waits for it, and the one kept offline is shown.
        assertEquals(listOf(null, null, null), days.map { it.forecastFrom })
        assertNull(state.weatherProblem)
        // Back from a canyon this morning: today is done, the days before are listed apart.
        assertEquals(listOf("Ce matin"), days[0].done.map { it.canyonName })
        assertEquals(listOf("Hier"), state.done.map { it.canyonName })

        viewModel.delete()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isGone)
        assertEquals(listOf("Riolan", "Ailleurs"), outings.outings.value.map { it.canyonName })
    }

    @Test
    fun `days beyond the forecast say when it comes, and a stay without forecast offline says why`() = runTest {
        val place = Place("Camping", 44.0, 6.8, id = 7)
        val stay = Stay(7, LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 22), id = 3)
        val viewModel = stayViewModel(stay, place, FakeOutingRepository(), cached = null)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Six days of forecast from the 14th: up to the 19th; the 20th gets its forecast on the 15th.
        assertEquals(
            listOf(null, null, LocalDate.of(2026, 7, 15), LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 17)),
            state.days.map {
                it.forecastFrom
            },
        )
        assertEquals(StayWeatherProblem.OFFLINE, state.weatherProblem)
    }

    @Test
    fun `an outing of the stay back after the sunset is flagged, and a draft can be taken off its day`() = runTest {
        val place = Place("Camping", 44.0, 6.8, id = 7)
        val stay = Stay(7, LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 16), id = 3)
        val outings = FakeOutingRepository()
        val late = outings.save(outingOn(LocalDate.of(2026, 7, 15), "Tardive", stayId = 3, hour = 17).copy(tracked = false))
        val early = outings.save(outingOn(LocalDate.of(2026, 7, 16), "Matinale", stayId = 3))
        val viewModel = stayViewModel(stay, place, outings, cached = null)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val signals = viewModel.uiState.value.signals
        // Back at 21:00 at worst, the sun sets around 21:10 there: too close.
        assertTrue(signals.getValue(late).endsAtNight)
        assertEquals(false, signals.getValue(early).endsAtNight)

        // A followed outing is changed from its plan; a draft leaves its day.
        viewModel.removeOuting(outings.get(early)!!)
        viewModel.removeOuting(outings.get(late)!!)
        advanceUntilIdle()
        assertEquals(listOf("Matinale"), outings.outings.value.map { it.canyonName })
    }

    @Test
    fun `the canyons around the stay come nearest by road first, and a filter touched again shows them all`() = runTest {
        val place = Place("Camping", 44.0, 6.8, id = 7)
        val drives = FakeDriveTimeRepository().apply { manual[7L to 1] = DriveTime(50, DriveTimeSource.MANUAL) }
        val viewModel = pickerViewModel(place, drives)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(LocalDate.of(2026, 7, 15), state.date)
        assertEquals(listOf(2, 1), state.candidates.map { it.canyon.id })
        assertEquals(listOf(DriveTimeSource.ESTIMATE, DriveTimeSource.MANUAL), state.candidates.map { it.drive.source })

        viewModel.toggleFilter(StayFilter.NOT_DESCENDED)
        advanceUntilIdle()
        assertEquals(listOf(1), viewModel.uiState.value.candidates.map { it.canyon.id })
        viewModel.toggleFilter(StayFilter.NOT_DESCENDED)
        advanceUntilIdle()
        assertEquals(StayFilter.ALL, viewModel.uiState.value.filter)
    }

    @Test
    fun `a name finds a canyon beyond the radius, and a canyon planned another day of the stay says so`() = runTest {
        val place = Place("Camping", 44.0, 6.8, id = 7)
        val outings = FakeOutingRepository()
        outings.save(outingOn(LocalDate.of(2026, 7, 16), "Riolan", stayId = 3).copy(canyonId = 1, tracked = false))
        val viewModel = pickerViewModel(place, FakeDriveTimeRepository(), outings)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 7, 16), viewModel.uiState.value.candidates.single { it.canyon.id == 1 }.plannedOn)

        // One letter is not a name yet.
        viewModel.search("l")
        advanceUntilIdle()
        assertEquals(listOf(2, 1), viewModel.uiState.value.candidates.map { it.canyon.id })
        viewModel.search("Loin")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isSearching)
        assertEquals(listOf(3), viewModel.uiState.value.candidates.map { it.canyon.id })
    }

    @Test
    fun `online, the real drive to the nearest canyons replaces the estimate`() = runTest {
        val place = Place("Camping", 44.0, 6.8, id = 7)
        val drives = FakeDriveTimeRepository(route = DriveTime(35, DriveTimeSource.ROUTE, 22.0))
        val viewModel = pickerViewModel(place, drives, online = true)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val candidates = viewModel.uiState.value.candidates
        assertEquals(listOf(DriveTimeSource.ROUTE, DriveTimeSource.ROUTE), candidates.map { it.drive.source })
        assertEquals(listOf(35, 35), candidates.map { it.drive.minutes })
    }

    @Test
    fun `the outing settings are changed within bounds and reset`() = runTest {
        val preferences = FakeOutingPreferencesRepository()
        val viewModel = OutingSettingsViewModel(
            preferences,
            GetPersonalPaceUseCase(
                mockk<LogbookRepository> { every { observeAll() } returns flowOf(emptyList()) },
                mockk<CanyonRepository>(),
            ),
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.update { it.copy(prepMinutes = 200, defaultGroupLevel = GroupLevel.WITH_BEGINNERS) }
        advanceUntilIdle()
        assertEquals(OutingSettings(prepMinutes = 120, defaultGroupLevel = GroupLevel.WITH_BEGINNERS), preferences.settings.value)
        assertNull(viewModel.uiState.value.personalPace)

        viewModel.reset()
        advanceUntilIdle()
        assertEquals(OutingSettings(), preferences.settings.value)
    }

    @Test
    fun `the outings list puts the one under way first, then the stays, the outings to come and the drafts apart`() = runTest {
        val outings = FakeOutingRepository()
        outings.save(
            PlannedOuting(1, "Matin", now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(3)), now.plus(Duration.ofHours(5))),
        )
        outings.save(PlannedOuting(2, "Demain", now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(1))))
        outings.save(outingOn(LocalDate.of(2026, 7, 15), "Brouillon").copy(tracked = false))
        outings.save(outingOn(LocalDate.of(2026, 7, 15), "Du séjour", stayId = 3).copy(tracked = false))
        val stays = FakeStayRepository(
            listOf(
                Stay(7, LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 16), id = 3),
                Stay(7, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 5), id = 4),
            ),
        )
        val logbook = listOf(LogbookEntry(canyonId = 50, canyonName = "Fait", date = LocalDate.of(2026, 7, 3)))
        val viewModel = outingsViewModel(outings, stays, FakePlaceRepository(listOf(Place("Camping", 44.0, 6.8, id = 7))), logbook)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Matin", state.underway?.canyonName)
        assertEquals(listOf("Demain"), state.upcoming.map { it.canyonName })
        assertEquals(listOf("Brouillon"), state.drafts.map { it.canyonName })
        assertEquals(listOf(Triple(3L, "Camping", 1)), state.stays.map { Triple(it.stay.id, it.place?.name, it.outings) })
        // The stay over is apart, with the canyons done during it.
        assertEquals(listOf(4L to 1), state.pastStays.map { it.stay.id to it.done })
    }

    private fun stayEditViewModel(
        handle: SavedStateHandle,
        stays: FakeStayRepository,
        places: FakePlaceRepository,
        outings: FakeOutingRepository = FakeOutingRepository(),
    ) = StayEditViewModel(
        savedStateHandle = handle,
        stayRepository = stays,
        placeRepository = places,
        outingRepository = outings,
        saveStayUseCase = SaveStayUseCase(stays, outings, clock),
        preferencesRepository = mockk<PreferencesRepository> { every { getLastMapPosition() } returns null },
        clock = clock,
    )

    private fun outingsViewModel(
        outings: FakeOutingRepository,
        stays: FakeStayRepository,
        places: FakePlaceRepository,
        logbook: List<LogbookEntry> = emptyList(),
    ) = OutingsViewModel(outings, stays, places, mockk<LogbookRepository> { every { observeAll() } returns flowOf(logbook) }, clock)

    private fun stayViewModel(
        stay: Stay,
        place: Place,
        outings: FakeOutingRepository,
        cached: CanyonWeather?,
        logbook: List<LogbookEntry> = emptyList(),
    ): StayViewModel {
        val weather = mockk<WeatherRepository> { coEvery { getCachedPlaceWeather(place) } returns cached }
        // Every canyon starts near the campsite.
        val canyons = mockk<CanyonRepository> {
            coEvery { getCanyonPreview(any()) } answers {
                val id = firstArg<Int>()
                Result.success(
                    CanyonDetail(
                        canyon = Canyon(
                            id = id,
                            nom = "Canyon $id",
                            nomComplet = "Canyon $id",
                            pays = "France",
                            commune = "",
                            cotation = "v3a3II",
                            url = "",
                        ),
                        geoPoints = listOf(GeoPoint(canyonId = id, type = GeoPointType.PARKING_AMONT, latitude = 44.0, longitude = 6.8)),
                    ),
                )
            }
        }
        return StayViewModel(
            savedStateHandle = SavedStateHandle(mapOf("stayId" to stay.id)),
            stayRepository = FakeStayRepository(listOf(stay), outings),
            placeRepository = FakePlaceRepository(listOf(place)),
            outingRepository = outings,
            logbookRepository = mockk<LogbookRepository> { every { observeAll() } returns flowOf(logbook) },
            canyonRepository = canyons,
            debitRepository = mockk<DebitRepository> { every { observeLatestKnownDebits(any()) } returns flowOf(emptyMap()) },
            getPlaceWeatherUseCase = GetPlaceWeatherUseCase(weather),
            connectivityObserver = mockk<ConnectivityObserver> { every { isCurrentlyOnline() } returns false },
            clock = clock,
        )
    }

    private fun pickerViewModel(
        place: Place,
        drives: FakeDriveTimeRepository,
        outings: FakeOutingRepository = FakeOutingRepository(),
        online: Boolean = false,
    ): StayCanyonPickerViewModel {
        val catalog = listOf(
            searchItem(1, "Riolan", 44.05, 6.85),
            searchItem(2, "Bollinette", 44.02, 6.82),
            searchItem(3, "Loin", 46.0, 7.5),
            searchItem(4, "Interdit", 44.01, 6.81, forbidden = true),
        )
        return StayCanyonPickerViewModel(
            savedStateHandle = SavedStateHandle(mapOf("stayId" to 3L, "date" to "2026-07-15")),
            stayRepository = FakeStayRepository(listOf(Stay(7, LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 16), id = 3))),
            placeRepository = FakePlaceRepository(listOf(place)),
            canyonRepository = mockk<CanyonRepository> {
                every { observeSearchCatalog() } returns flowOf(catalog)
                coEvery { getCanyonPreview(any()) } answers {
                    val id = firstArg<Int>()
                    Result.success(
                        CanyonDetail(
                            canyon = Canyon(
                                id = id,
                                nom = "Canyon $id",
                                nomComplet = "Canyon $id",
                                pays = "France",
                                commune = "",
                                cotation = "v3a3II",
                                url = "",
                            ),
                            geoPoints = listOf(
                                GeoPoint(canyonId = id, type = GeoPointType.PARKING_AMONT, latitude = 44.03, longitude = 6.83),
                            ),
                        ),
                    )
                }
            },
            logbookRepository = mockk<LogbookRepository> { every { observeDescendedCanyonIds() } returns flowOf(setOf(2)) },
            outingRepository = outings,
            driveTimeRepository = drives,
            debitRepository = mockk<DebitRepository> { every { observeLatestKnownDebits(any()) } returns flowOf(emptyMap()) },
            connectivityObserver = mockk<ConnectivityObserver> { every { isCurrentlyOnline() } returns online },
            clock = clock,
        )
    }

    private fun outingOn(date: LocalDate, name: String, placeId: Long? = null, stayId: Long? = null, hour: Int = 9): PlannedOuting {
        val start = date.atTime(hour, 0).atZone(zone).toInstant()
        return PlannedOuting(
            canyonId = name.length,
            canyonName = name,
            start = start,
            expectedBack = start.plus(Duration.ofHours(4)),
            alertAt = start.plus(Duration.ofHours(6)),
            choices = OutingChoices(date, LocalTime.of(9, 0)).withPlace(placeId),
            stayId = stayId,
        )
    }

    private fun searchItem(id: Int, name: String, latitude: Double, longitude: Double, forbidden: Boolean = false) = CanyonSearchItem(
        id = id,
        nom = name,
        nomComplet = name,
        pays = "France",
        cotation = "v3a3II",
        cotationRating = CotationRating.parse("v3a3II"),
        isForbidden = forbidden,
        representativeLat = latitude,
        representativeLng = longitude,
        url = "https://example.test/$id",
        searchableText = name.lowercase(),
        normalizedNom = name.lowercase(),
        normalizedNomComplet = name.lowercase(),
    )
}
