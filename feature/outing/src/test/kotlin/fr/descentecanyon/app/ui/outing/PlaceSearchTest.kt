package fr.descentecanyon.app.ui.outing

import fr.descentecanyon.app.domain.model.FoundPlace
import fr.descentecanyon.app.domain.model.FoundPlaceKind
import fr.descentecanyon.app.testutil.FakePlaceSearchRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import fr.descentecanyon.app.ui.map.MapCameraState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSearchTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val near = MapCameraState(44.0, 7.5, 12.0)
    private val campsite = FoundPlace("Camping du Torrent", "06430 Tende, France", 44.08, 7.59, FoundPlaceKind.CAMPSITE)

    @Test
    fun `the search waits for a pause in the typing, and asks nothing under three letters`() = runTest {
        val repository = FakePlaceSearchRepository(listOf(campsite))
        val viewModel = PlaceSearchViewModel(repository)

        viewModel.updateQuery("ca", near, "fr")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), repository.queries)
        assertFalse(viewModel.uiState.value.isSearching)

        viewModel.updateQuery("cam", near, "fr")
        viewModel.updateQuery("camping tor", near, "fr")
        assertTrue(viewModel.uiState.value.isSearching)
        advanceTimeBy(100)
        assertEquals(emptyList<String>(), repository.queries)
        advanceUntilIdle()
        assertEquals(listOf("camping tor"), repository.queries)
        assertEquals(listOf(campsite), viewModel.uiState.value.results)
        assertFalse(viewModel.uiState.value.isSearching)
    }

    @Test
    fun `a failed search says so, and the search key tries again at once`() = runTest {
        val repository = FakePlaceSearchRepository(results = null)
        val viewModel = PlaceSearchViewModel(repository)

        viewModel.updateQuery("camping", near, "fr")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.failed)

        repository.results = listOf(campsite)
        viewModel.searchNow(near, "fr")
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.failed)
        assertEquals(listOf(campsite), viewModel.uiState.value.results)
        assertEquals(listOf("camping", "camping"), repository.queries)

        // Nothing new to search: the key does not ask again.
        viewModel.searchNow(near, "fr")
        advanceUntilIdle()
        assertEquals(2, repository.queries.size)

        viewModel.clear()
        assertEquals(PlaceSearchUiState(), viewModel.uiState.value)
    }
}
