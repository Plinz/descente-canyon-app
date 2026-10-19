package fr.descentecanyon.app.ui.search

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.SearchCriteria
import fr.descentecanyon.app.domain.model.SearchSortField
import fr.descentecanyon.app.domain.model.SortDirection
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.RecentCanyonsRepository
import fr.descentecanyon.app.domain.usecase.SearchCanyonsUseCase
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
    private val searchCanyonsUseCase = SearchCanyonsUseCase(canyonRepository)
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
        val viewModel = SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher, recentCanyonsRepository)

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
        val viewModel = SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher, recentCanyonsRepository)

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
        val viewModel = SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher, recentCanyonsRepository)

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
        val viewModel = SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher, recentCanyonsRepository)

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
        val viewModel = SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher, recentCanyonsRepository)

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
        val viewModel = SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher, recentCanyonsRepository)

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
        val viewModel = SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher, recentCanyonsRepository)

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
        val viewModel = SearchViewModel(searchCanyonsUseCase, SavedStateHandle(), mainDispatcherRule.dispatcher, recentCanyonsRepository)

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
        )

        advanceTimeBy(250)
        advanceUntilIdle()

        assertEquals(SearchResultViewMode.MAP, viewModel.uiState.value.resultViewMode)
    }

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
