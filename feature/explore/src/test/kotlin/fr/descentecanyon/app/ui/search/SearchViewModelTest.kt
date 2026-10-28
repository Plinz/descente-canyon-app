package fr.descentecanyon.app.ui.search

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.DriveTime
import fr.descentecanyon.app.domain.model.DriveTimeSource
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.OutingChoices
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.SearchCriteria
import fr.descentecanyon.app.domain.model.SearchSortField
import fr.descentecanyon.app.domain.model.SortDirection
import fr.descentecanyon.app.domain.model.Stay
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.domain.repository.RecentCanyonsRepository
import fr.descentecanyon.app.domain.usecase.ChooseStayCanyonUseCase
import fr.descentecanyon.app.domain.usecase.SearchCanyonsUseCase
import fr.descentecanyon.app.testutil.FakeDriveTimeRepository
import fr.descentecanyon.app.testutil.FakeOutingRepository
import fr.descentecanyon.app.testutil.FakePlaceRepository
import fr.descentecanyon.app.testutil.FakeStayRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val canyonRepository = mockk<CanyonRepository>()
    private val searchCanyonsUseCase = SearchCanyonsUseCase(canyonRepository, noOutings())
    private val recentCanyons = MutableStateFlow<List<CanyonSummary>>(emptyList())
    private val recentCanyonsRepository = mockk<RecentCanyonsRepository>(relaxUnitFun = true) {
        every { observeRecentCanyons(any()) } returns recentCanyons
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `recently viewed canyons are offered and can be cleared`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(listOf(canyon(id = 1)))
        recentCanyons.value =
            listOf(CanyonSummary(id = 7, nom = "Riolan", pays = "France", cotation = "v4a4III", url = "https://example.test/7"))
        val viewModel =
            SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher, recentCanyonsRepository, stayChoice())

        advanceTimeBy(250)
        advanceUntilIdle()
        viewModel.clearRecentCanyons()
        advanceUntilIdle()

        assertEquals(listOf(7), viewModel.uiState.value.recentCanyons.map { it.id })
        coVerify { recentCanyonsRepository.clear() }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `broad catalog is deferred while keeping available filters`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(
            listOf(
                canyon(id = 1, pays = "France", departement = "Ain"),
                canyon(id = 2, pays = "Espagne", departement = "Huesca"),
            ),
        )
        val viewModel =
            SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher, recentCanyonsRepository, stayChoice())

        advanceTimeBy(250)
        advanceUntilIdle()

        assertEquals(emptyList<Int>(), viewModel.uiState.value.results.map { it.id })
        assertEquals(true, viewModel.uiState.value.isResultListDeferred)
        assertEquals(2, viewModel.uiState.value.totalResultsCount)
        assertEquals(listOf("Espagne", "France"), viewModel.uiState.value.zones.map { it.name })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `filters narrow results and changing country resets department`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(
            listOf(
                canyon(id = 1, pays = "France", departement = "Ain"),
                canyon(id = 2, pays = "France", departement = "Isere", isFavorite = true),
                canyon(id = 3, pays = "Espagne", departement = "Huesca"),
            ),
        )
        val viewModel =
            SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher, recentCanyonsRepository, stayChoice())

        advanceTimeBy(250)
        advanceUntilIdle()
        viewModel.onCriteriaChanged(SearchCriteria(selectedCountry = "France", selectedDepartment = "Isere"))
        advanceTimeBy(250)
        advanceUntilIdle()

        assertEquals(listOf(2), viewModel.uiState.value.results.map { it.id })
        assertEquals(1, viewModel.uiState.value.scrollResetRequestId)

        viewModel.onCriteriaChanged(viewModel.uiState.value.criteria.copy(selectedCountry = "Espagne"))
        advanceTimeBy(250)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.criteria.selectedDepartment)
        assertEquals(listOf(3), viewModel.uiState.value.results.map { it.id })
        assertEquals(2, viewModel.uiState.value.scrollResetRequestId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `reselecting same sort toggles direction`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(
            listOf(
                canyon(id = 1, nom = "Alpha", interet = 2f),
                canyon(id = 2, nom = "Beta", interet = 4f),
            ),
        )
        val viewModel =
            SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher, recentCanyonsRepository, stayChoice())

        advanceTimeBy(250)
        advanceUntilIdle()
        viewModel.onQueryChanged("al")
        advanceTimeBy(250)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.scrollResetRequestId)

        viewModel.onSortSelected(SearchSortField.NAME)
        advanceTimeBy(250)
        advanceUntilIdle()
        assertEquals(SortDirection.ASC, viewModel.uiState.value.criteria.sortDirection)
        assertEquals(listOf(1), viewModel.uiState.value.results.map { it.id })
        assertEquals(2, viewModel.uiState.value.scrollResetRequestId)

        // Picking the current field again changes nothing: the order has its own control.
        viewModel.onSortSelected(SearchSortField.NAME)
        advanceTimeBy(250)
        advanceUntilIdle()
        assertEquals(SortDirection.ASC, viewModel.uiState.value.criteria.sortDirection)
        assertEquals(2, viewModel.uiState.value.scrollResetRequestId)

        viewModel.onSortDirectionSelected(SortDirection.DESC)
        advanceTimeBy(250)
        advanceUntilIdle()
        assertEquals(SearchSortField.NAME, viewModel.uiState.value.criteria.sortField)
        assertEquals(SortDirection.DESC, viewModel.uiState.value.criteria.sortDirection)
        assertEquals(listOf(1), viewModel.uiState.value.results.map { it.id })
        assertEquals(3, viewModel.uiState.value.scrollResetRequestId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `applied query change requests a list scroll reset`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(
            listOf(
                canyon(id = 1, nom = "Alpha"),
                canyon(id = 2, nom = "Alpin"),
            ),
        )
        val viewModel =
            SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher, recentCanyonsRepository, stayChoice())

        advanceTimeBy(250)
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.scrollResetRequestId)

        viewModel.onQueryChanged("al")
        advanceTimeBy(199)
        assertEquals(0, viewModel.uiState.value.scrollResetRequestId)

        advanceTimeBy(1)
        advanceUntilIdle()
        assertEquals(setOf(1, 2), viewModel.uiState.value.results.map { it.id }.toSet())
        assertEquals(1, viewModel.uiState.value.scrollResetRequestId)

        viewModel.clearQuery()
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.scrollResetRequestId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `deleting query character resets scroll after distance results are recomputed`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(
            listOf(
                canyon(id = 1, nom = "Ver proche", latitude = 43.70, longitude = 6.90),
                canyon(id = 2, nom = "Versoud loin", latitude = 44.00, longitude = 7.30),
            ),
        )
        val viewModel =
            SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher, recentCanyonsRepository, stayChoice())

        advanceTimeBy(250)
        advanceUntilIdle()
        viewModel.onUserLocationUpdated(43.70, 6.90)
        viewModel.onSortSelected(SearchSortField.DISTANCE)
        advanceTimeBy(250)
        advanceUntilIdle()

        viewModel.onQueryChanged("vers")
        advanceTimeBy(250)
        advanceUntilIdle()
        assertEquals(listOf(2), viewModel.uiState.value.results.map { it.id })
        val versScrollResetId = viewModel.uiState.value.scrollResetRequestId

        viewModel.onQueryChanged("ver")
        advanceUntilIdle()

        assertEquals(listOf(1, 2), viewModel.uiState.value.results.map { it.id })
        assertEquals(versScrollResetId + 1, viewModel.uiState.value.scrollResetRequestId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `selecting a canyon does not request a list scroll reset`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(listOf(canyon(id = 1)))
        val viewModel =
            SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher, recentCanyonsRepository, stayChoice())

        advanceTimeBy(250)
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.scrollResetRequestId)

        viewModel.selectCanyon(1)
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.scrollResetRequestId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `location update enables distance sort and map selection`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(
            listOf(
                canyon(id = 1, nom = "Alpha", latitude = 43.70, longitude = 6.90),
                canyon(id = 2, nom = "Beta", latitude = 43.90, longitude = 7.30),
            ),
        )
        val viewModel =
            SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher, recentCanyonsRepository, stayChoice())

        advanceTimeBy(250)
        advanceUntilIdle()

        viewModel.onLocationPermissionResult(true)
        viewModel.onUserLocationUpdated(43.705, 6.905)
        viewModel.onSortSelected(SearchSortField.DISTANCE)
        viewModel.onResultViewModeChanged(SearchResultViewMode.MAP)
        viewModel.selectCanyon(1)
        advanceTimeBy(250)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasLocationPermission)
        assertEquals(SearchSortField.DISTANCE, viewModel.uiState.value.criteria.sortField)
        assertEquals(listOf(1, 2), viewModel.uiState.value.results.map { it.id })
        assertEquals(SearchResultViewMode.MAP, viewModel.uiState.value.resultViewMode)
        assertEquals(1, viewModel.uiState.value.selectedCanyon?.id)

        viewModel.clearSelectedCanyon()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.selectedCanyon)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `saved state restores map result view mode`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(listOf(canyon(id = 1)))
        val viewModel = SearchViewModel(
            searchCanyonsUseCase,
            SavedStateHandle(mapOf("search_result_view_mode" to SearchResultViewMode.MAP.name)),
            mainDispatcherRule.dispatcher,
            recentCanyonsRepository,
            stayChoice(),
        )

        advanceTimeBy(250)
        advanceUntilIdle()

        assertEquals(SearchResultViewMode.MAP, viewModel.uiState.value.resultViewMode)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `for a day of a stay, the canyons around its place come nearest first, with the drive and the other day planned`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(stayCatalog)
        val outings = FakeOutingRepository()
        val start = LocalDate.of(2026, 7, 16).atTime(9, 0).atZone(zone).toInstant()
        outings.save(
            PlannedOuting(
                1,
                "Riolan",
                start,
                start,
                start,
                choices = OutingChoices(LocalDate.of(2026, 7, 16), LocalTime.of(9, 0)),
                stayId = 3,
            ),
        )
        val drives = FakeDriveTimeRepository().apply { manual[7L to 1] = DriveTime(50, DriveTimeSource.MANUAL) }
        val viewModel = stayViewModel(stayChoice(outings, drives))
        backgroundScope.launch { viewModel.stayPick.collect {} }
        advanceTimeBy(250)
        advanceUntilIdle()

        assertTrue(viewModel.isStayPick)
        val state = viewModel.uiState.value
        // Within 40 km of the campsite, nearest first; the forbidden one cannot be planned.
        assertEquals(listOf(2, 1), state.results.map { it.id })
        assertEquals(SearchSortField.DISTANCE, state.criteria.sortField)
        assertEquals(40, state.criteria.maxDistanceKm)
        val pick = viewModel.stayPick.value!!
        assertEquals(LocalDate.of(2026, 7, 15), pick.date)
        assertEquals("Camping", pick.place.name)
        assertEquals(mapOf(1 to LocalDate.of(2026, 7, 16)), pick.plannedOn)
        assertEquals(DriveTime(50, DriveTimeSource.MANUAL), pick.driveTo(state.results[1]))
        assertEquals(DriveTimeSource.ESTIMATE, pick.driveTo(state.results[0])?.source)

        // Any filter or order of the search, still around the campsite.
        viewModel.onSortSelected(SearchSortField.NAME)
        advanceTimeBy(250)
        advanceUntilIdle()
        assertEquals(listOf(2, 1), viewModel.uiState.value.results.map { it.id })
        // Without the distance filter, still from the campsite.
        viewModel.onSortSelected(SearchSortField.DISTANCE)
        viewModel.onCriteriaChanged(viewModel.uiState.value.criteria.copy(maxDistanceKm = null))
        advanceTimeBy(250)
        advanceUntilIdle()
        assertEquals(listOf(2, 1, 3), viewModel.uiState.value.results.map { it.id })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `online, the real drive to the nearest canyons of a stay replaces the estimate`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(stayCatalog)
        coEvery { canyonRepository.getCanyonPreview(any()) } answers {
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
                    geoPoints = listOf(GeoPoint(canyonId = id, type = GeoPointType.PARKING_AMONT, latitude = 44.03, longitude = 6.83)),
                ),
            )
        }
        val drives = FakeDriveTimeRepository(route = DriveTime(35, DriveTimeSource.ROUTE, 22.0))
        val viewModel = stayViewModel(stayChoice(drives = drives, online = true))
        backgroundScope.launch { viewModel.stayPick.collect {} }
        advanceTimeBy(250)
        advanceUntilIdle()

        val pick = viewModel.stayPick.value!!
        assertEquals(listOf(35, 35), viewModel.uiState.value.results.map { pick.driveTo(it)?.minutes })
        assertEquals(DriveTimeSource.ROUTE, pick.driveTo(viewModel.uiState.value.results.first())?.source)
    }

    private val zone = ZoneId.of("Europe/Paris")
    private val clock = Clock.fixed(LocalDateTime.of(2026, 7, 14, 10, 0).atZone(zone).toInstant(), zone)

    /** Around the campsite: two canyons near, one forbidden near, one far. */
    private val stayCatalog = listOf(
        canyon(id = 1, nom = "Riolan", latitude = 44.05, longitude = 6.85),
        canyon(id = 2, nom = "Bollinette", latitude = 44.02, longitude = 6.82),
        canyon(id = 3, nom = "Loin", latitude = 46.0, longitude = 7.5),
        canyon(id = 4, nom = "Interdit", latitude = 44.01, longitude = 6.81).copy(isForbidden = true),
    )

    private fun stayViewModel(choice: ChooseStayCanyonUseCase) = SearchViewModel(
        searchCanyonsUseCase,
        SavedStateHandle(mapOf("stayId" to 3L, "date" to "2026-07-15", "outingId" to 0L)),
        mainDispatcherRule.dispatcher,
        recentCanyonsRepository,
        choice,
    )

    /** A stay at a campsite from the 14th to the 16th of July. */
    private fun stayChoice(
        outings: FakeOutingRepository = FakeOutingRepository(),
        drives: FakeDriveTimeRepository = FakeDriveTimeRepository(),
        online: Boolean = false,
    ) = ChooseStayCanyonUseCase(
        stayRepository = FakeStayRepository(listOf(Stay(7, LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 16), id = 3))),
        placeRepository = FakePlaceRepository(listOf(Place("Camping", 44.0, 6.8, id = 7))),
        outingRepository = outings,
        canyonRepository = canyonRepository,
        driveTimeRepository = drives,
        connectivityObserver = mockk<ConnectivityObserver> { every { isCurrentlyOnline() } returns online },
        clock = clock,
    )

    private fun canyon(
        id: Int,
        nom: String = "Canyon $id",
        pays: String = "France",
        departement: String? = "Ain",
        interet: Float? = 3f,
        isFavorite: Boolean = false,
        latitude: Double? = null,
        longitude: Double? = null,
    ) = CanyonSearchItem(
        id = id,
        nom = nom,
        nomComplet = nom,
        pays = pays,
        countryTokens = pays.split(',').map(String::trim),
        departement = departement,
        departmentTokens = departement?.split(',')?.map(String::trim).orEmpty(),
        cotation = "v3a3III",
        cotationRating = CotationRating.parse("v3a3III"),
        interet = interet,
        isFavorite = isFavorite,
        representativeLat = latitude,
        representativeLng = longitude,
        url = "/canyoning/canyon/$id/test.html",
        searchableText = "$nom $pays ${departement.orEmpty()}".lowercase(),
        normalizedNom = nom.lowercase(),
        normalizedNomComplet = nom.lowercase(),
    )
}

/** A logbook without outings, for the catalog search. */
private fun noOutings(): LogbookRepository = mockk { every { observeDescendedCanyonIds() } returns flowOf(emptySet()) }
