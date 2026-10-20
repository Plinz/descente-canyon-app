package fr.descentecanyon.app.ui.outing

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.DriveTime
import fr.descentecanyon.app.domain.model.DriveTimeSource
import fr.descentecanyon.app.domain.model.GroupLevel
import fr.descentecanyon.app.domain.model.OutingSettings
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.domain.repository.WeatherRepository
import fr.descentecanyon.app.domain.usecase.GetCanyonWeatherUseCase
import fr.descentecanyon.app.domain.usecase.GetPersonalPaceUseCase
import fr.descentecanyon.app.testutil.FakeDriveTimeRepository
import fr.descentecanyon.app.testutil.FakeOutingPreferencesRepository
import fr.descentecanyon.app.testutil.FakeOutingRepository
import fr.descentecanyon.app.testutil.FakePlaceRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `the days of a stay still to come, else the coming week`() {
        val today = LocalDate.of(2026, 7, 14)
        val stay = Place("Camping", 44.0, 6.8, LocalDate.of(2026, 7, 12), LocalDate.of(2026, 7, 16))
        assertEquals((14..16).map { LocalDate.of(2026, 7, it) }, StayPlannerViewModel.stayDays(stay, today))
        assertEquals(7, StayPlannerViewModel.stayDays(Place("Maison", 45.0, 5.0), today).size)
        assertEquals(LocalDate.of(2026, 7, 14), StayPlannerViewModel.stayDays(null, today).first())
    }

    @Test
    fun `the canyons around the place come nearest by road first, with the outings planned`() = runTest {
        val place = Place("Camping", 44.0, 6.8, LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 16), id = 7)
        val outings = FakeOutingRepository()
        outings.save(PlannedOuting(2, "Bollinette", onJuly15(9), onJuly15(14), onJuly15(17)))
        val drives = FakeDriveTimeRepository().apply { manual[7L to 1] = DriveTime(50, DriveTimeSource.MANUAL) }
        val catalog = listOf(
            searchItem(1, "Riolan", 44.05, 6.85),
            searchItem(2, "Bollinette", 44.02, 6.82),
            searchItem(3, "Loin", 46.0, 7.5),
            searchItem(4, "Interdit", 44.01, 6.81, forbidden = true),
        )
        val viewModel = StayPlannerViewModel(
            savedStateHandle = SavedStateHandle(mapOf("placeId" to 7L)),
            placeRepository = FakePlaceRepository(listOf(place)),
            canyonRepository = mockk<CanyonRepository> { every { observeSearchCatalog() } returns flowOf(catalog) },
            logbookRepository = mockk<LogbookRepository> { every { observeDescendedCanyonIds() } returns flowOf(setOf(2)) },
            outingRepository = outings,
            driveTimeRepository = drives,
            debitRepository = mockk<DebitRepository> { every { observeLatestKnownDebits(any()) } returns flowOf(emptyMap()) },
            getCanyonWeatherUseCase = GetCanyonWeatherUseCase(
                mockk<WeatherRepository> {
                    coEvery { getCachedCanyonWeather(any()) } returns
                        null
                },
            ),
            connectivityObserver = mockk<ConnectivityObserver> { every { isCurrentlyOnline() } returns false },
            clock = clock,
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(2, 1), state.candidates.map { it.canyon.id })
        assertEquals(listOf(DriveTimeSource.ESTIMATE, DriveTimeSource.MANUAL), state.candidates.map { it.drive.source })
        assertEquals((14..16).map { LocalDate.of(2026, 7, it) }, state.days)
        assertEquals(setOf(2 to LocalDate.of(2026, 7, 15)), state.planned.keys)

        viewModel.selectFilter(StayFilter.NOT_DESCENDED)
        advanceUntilIdle()
        assertEquals(listOf(1), viewModel.uiState.value.candidates.map { it.canyon.id })
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
    fun `the outings list puts the one under way apart from those to come`() = runTest {
        val outings = FakeOutingRepository()
        outings.save(
            PlannedOuting(1, "Matin", now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(3)), now.plus(Duration.ofHours(5))),
        )
        outings.save(PlannedOuting(2, "Demain", now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(1))))
        val viewModel = OutingsViewModel(outings, FakePlaceRepository(), clock)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Matin", state.underway?.canyonName)
        assertEquals(listOf("Demain"), state.upcoming.map { it.canyonName })
    }

    private fun onJuly15(hour: Int) = LocalDateTime.of(2026, 7, 15, hour, 0).atZone(zone).toInstant()

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
