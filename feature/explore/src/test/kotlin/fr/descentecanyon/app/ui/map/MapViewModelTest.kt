package fr.descentecanyon.app.ui.map

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.MapPosition
import fr.descentecanyon.app.domain.model.SearchCriteria
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.domain.usecase.SearchCanyonsUseCase
import fr.descentecanyon.app.domain.usecase.ToggleFavoriteUseCase
import fr.descentecanyon.app.testutil.FakePreferencesRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MapViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val canyonRepository = mockk<CanyonRepository>()
    private val favoritesRepository = mockk<FavoritesRepository>()
    private val searchCanyonsUseCase = SearchCanyonsUseCase(canyonRepository)
    private val toggleFavoriteUseCase = ToggleFavoriteUseCase(favoritesRepository)
    private val preferences = FakePreferencesRepository()

    @Test
    fun `the map opens where it was left at the previous launch`() {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(emptyList())
        preferences.mapPosition = MapPosition(latitude = 43.96, longitude = 7.5, zoom = 11.0)

        val viewModel = mapViewModel()

        assertEquals(MapCameraState(latitude = 43.96, longitude = 7.5, zoom = 11.0), viewModel.uiState.value.cameraState)

        viewModel.onCameraChanged(MapCameraState(latitude = 45.0, longitude = 6.0, zoom = 9.0))
        assertEquals(MapPosition(latitude = 45.0, longitude = 6.0, zoom = 9.0), preferences.mapPosition)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `permission denial updates ui state`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(emptyList())
        val viewModel = mapViewModel()

        viewModel.onLocationPermissionResult(false)

        assertFalse(viewModel.uiState.value.hasLocationPermission)
        assertTrue(viewModel.uiState.value.hasRequestedLocationPermission)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `focus around user stores location and increments request id`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(listOf(catalogItem(id = 42)))
        val viewModel = mapViewModel()

        advanceUntilIdle()

        viewModel.focusAroundUser(43.7, 6.9)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(43.7, viewModel.uiState.value.userLatitude)
        assertEquals(6.9, viewModel.uiState.value.userLongitude)
        assertEquals(1, viewModel.uiState.value.focusLocationRequestId)
        assertEquals(listOf(42), viewModel.uiState.value.mapCanyons.map { it.id })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `select canyon exposes bottom sheet item`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(listOf(catalogItem(id = 7, nom = "Aiglun")))
        val viewModel = mapViewModel()

        advanceUntilIdle()
        viewModel.selectCanyon(7)

        assertEquals(7, viewModel.uiState.value.selectedCanyon?.id)
        viewModel.clearSelectedCanyon()
        assertEquals(null, viewModel.uiState.value.selectedCanyon)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `selected canyon favorite can be toggled`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(listOf(catalogItem(id = 7)))
        every { favoritesRepository.isFavorite(7) } returns flowOf(false)
        coEvery { favoritesRepository.addFavorite(7) } returns Unit
        val viewModel = mapViewModel()

        advanceUntilIdle()
        viewModel.selectCanyon(7)
        viewModel.toggleSelectedCanyonFavorite()
        advanceUntilIdle()

        coVerify { favoritesRepository.addFavorite(7) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `saved state restores camera and selected canyon`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(listOf(catalogItem(id = 9, nom = "Riou")))
        val savedStateHandle = SavedStateHandle(
            mapOf(
                "map_camera_latitude" to 43.62,
                "map_camera_longitude" to 6.71,
                "map_camera_zoom" to 9.5,
                "map_selected_canyon_id" to 9,
                "map_user_latitude" to 43.61,
                "map_user_longitude" to 6.72,
                "map_focus_location_request_id" to 3,
            ),
        )

        val viewModel = mapViewModel(savedStateHandle)

        advanceUntilIdle()

        assertEquals(43.62, viewModel.uiState.value.cameraState?.latitude)
        assertEquals(6.71, viewModel.uiState.value.cameraState?.longitude)
        assertEquals(9.5, viewModel.uiState.value.cameraState?.zoom)
        assertEquals(9, viewModel.uiState.value.selectedCanyon?.id)
        assertEquals(43.61, viewModel.uiState.value.userLatitude)
        assertEquals(6.72, viewModel.uiState.value.userLongitude)
        assertEquals(3, viewModel.uiState.value.focusLocationRequestId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `filters update both map markers and result count`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(
            listOf(
                catalogItem(id = 1, country = "France"),
                catalogItem(id = 2, country = "Espagne"),
            ),
        )
        val viewModel = mapViewModel()

        advanceUntilIdle()
        viewModel.onCriteriaChanged(SearchCriteria(selectedCountry = "France"))
        advanceUntilIdle()

        assertEquals(listOf(1), viewModel.uiState.value.mapCanyons.map(CanyonSummary::id))
        assertEquals(1, viewModel.uiState.value.totalResultsCount)
        assertEquals("France", viewModel.uiState.value.criteria.selectedCountry)

        viewModel.clearAllFilters()
        advanceUntilIdle()

        assertEquals(listOf(1, 2), viewModel.uiState.value.mapCanyons.map(CanyonSummary::id))
        assertEquals(2, viewModel.uiState.value.totalResultsCount)
    }

    private fun catalogItem(
        id: Int,
        nom: String = "Riolan",
        country: String = "France",
        latitude: Double = 43.71,
        longitude: Double = 6.88,
    ) = CanyonSearchItem(
        id = id,
        nom = nom,
        nomComplet = nom,
        pays = country,
        countryTokens = listOf(country),
        cotation = "v4a4III",
        cotationRating = CotationRating.parse("v4a4III"),
        url = "/canyoning/canyon/$id/${nom.lowercase()}.html",
        searchableText = nom,
        normalizedNom = nom.lowercase(),
        normalizedNomComplet = nom.lowercase(),
        representativeLat = latitude,
        representativeLng = longitude,
    )

    private fun mapViewModel(savedStateHandle: SavedStateHandle = SavedStateHandle()): MapViewModel {
        every { favoritesRepository.getFavorites() } returns flowOf(emptyList())
        return MapViewModel(
            savedStateHandle = savedStateHandle,
            searchCanyonsUseCase = searchCanyonsUseCase,
            favoritesRepository = favoritesRepository,
            toggleFavoriteUseCase = toggleFavoriteUseCase,
            preferencesRepository = preferences,
        )
    }
}
