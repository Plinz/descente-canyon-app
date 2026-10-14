package fr.descentecanyon.app.ui.favorites

import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FavoritesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val favoritesRepository = mockk<FavoritesRepository>()

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `favorites flow populates ui state`() = runTest {
        every { favoritesRepository.getFavorites() } returns MutableStateFlow(listOf(summary(7)))

        val viewModel = FavoritesViewModel(favoritesRepository)
        advanceUntilIdle()

        assertEquals(listOf(7), viewModel.uiState.value.favorites.map { it.id })
        assertTrue(!viewModel.uiState.value.isLoading)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `remove favorite delegates to repository`() = runTest {
        every { favoritesRepository.getFavorites() } returns MutableStateFlow(listOf(summary(7)))
        coEvery { favoritesRepository.removeFavorite(7) } returns Unit

        val viewModel = FavoritesViewModel(favoritesRepository)
        advanceUntilIdle()

        viewModel.removeFavorite(7)
        advanceUntilIdle()

        coVerify(exactly = 1) { favoritesRepository.removeFavorite(7) }
    }

    private fun summary(id: Int) = CanyonSummary(
        id = id,
        nom = "Canyon $id",
        pays = "France",
        cotation = "v3a3III",
        url = "/canyoning/canyon/$id/test.html",
    )
}
