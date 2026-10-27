package fr.descentecanyon.app.ui.outing

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.DailyWeather
import fr.descentecanyon.app.domain.model.DriveTime
import fr.descentecanyon.app.domain.model.DriveTimeSource
import fr.descentecanyon.app.domain.model.GroupLevel
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
        val viewModel = StayEditViewModel(
            savedStateHandle = SavedStateHandle(),
            stayRepository = stays,
            placeRepository = places,
            preferencesRepository = mockk<PreferencesRepository> { every { getLastMapPosition() } returns null },
            clock = clock,
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(false, viewModel.uiState.value.canSave)

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
        val weather = mockk<WeatherRepository> {
            coEvery { getCachedPlaceWeather(place) } returns forecast
        }
        val viewModel = StayViewModel(
            savedStateHandle = SavedStateHandle(mapOf("stayId" to 3L)),
            stayRepository = FakeStayRepository(listOf(stay), outings),
            placeRepository = FakePlaceRepository(listOf(place)),
            outingRepository = outings,
            getPlaceWeatherUseCase = GetPlaceWeatherUseCase(weather),
            connectivityObserver = mockk<ConnectivityObserver> { every { isCurrentlyOnline() } returns false },
            clock = clock,
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val days = viewModel.uiState.value.days
        assertEquals((14..16).map { LocalDate.of(2026, 7, it) }, days.map { it.date })
        assertEquals(listOf(emptyList(), listOf("Bollinette"), listOf("Riolan")), days.map { day -> day.outings.map { it.canyonName } })
        assertEquals(26.0, days[1].weather?.precipitationSumMm)
        assertNull(days[0].weather)

        viewModel.delete()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isGone)
        assertEquals(listOf("Riolan", "Ailleurs"), outings.outings.value.map { it.canyonName })
    }

    @Test
    fun `the canyons around the stay come nearest by road first, and a filter touched again shows them all`() = runTest {
        val place = Place("Camping", 44.0, 6.8, id = 7)
        val drives = FakeDriveTimeRepository().apply { manual[7L to 1] = DriveTime(50, DriveTimeSource.MANUAL) }
        val catalog = listOf(
            searchItem(1, "Riolan", 44.05, 6.85),
            searchItem(2, "Bollinette", 44.02, 6.82),
            searchItem(3, "Loin", 46.0, 7.5),
            searchItem(4, "Interdit", 44.01, 6.81, forbidden = true),
        )
        val viewModel = StayCanyonPickerViewModel(
            savedStateHandle = SavedStateHandle(mapOf("stayId" to 3L, "date" to "2026-07-15")),
            stayRepository = FakeStayRepository(listOf(Stay(7, LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 16), id = 3))),
            placeRepository = FakePlaceRepository(listOf(place)),
            canyonRepository = mockk<CanyonRepository> { every { observeSearchCatalog() } returns flowOf(catalog) },
            logbookRepository = mockk<LogbookRepository> { every { observeDescendedCanyonIds() } returns flowOf(setOf(2)) },
            driveTimeRepository = drives,
            debitRepository = mockk<DebitRepository> { every { observeLatestKnownDebits(any()) } returns flowOf(emptyMap()) },
            clock = clock,
        )
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
        val viewModel = OutingsViewModel(outings, stays, FakePlaceRepository(listOf(Place("Camping", 44.0, 6.8, id = 7))), clock)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Matin", state.underway?.canyonName)
        assertEquals(listOf("Demain"), state.upcoming.map { it.canyonName })
        assertEquals(listOf("Brouillon"), state.drafts.map { it.canyonName })
        // The stay over is not shown.
        assertEquals(listOf(Triple(3L, "Camping", 1)), state.stays.map { Triple(it.stay.id, it.place?.name, it.outings) })
    }

    private fun outingOn(date: LocalDate, name: String, placeId: Long? = null, stayId: Long? = null): PlannedOuting {
        val start = date.atTime(9, 0).atZone(zone).toInstant()
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
