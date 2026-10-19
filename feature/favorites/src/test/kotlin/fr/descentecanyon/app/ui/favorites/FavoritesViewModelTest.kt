package fr.descentecanyon.app.ui.favorites

import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FavoritesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val favoritesRepository = mockk<FavoritesRepository>(relaxUnitFun = true)
    private val latestDebits = MutableStateFlow<Map<Int, Debit>>(emptyMap())
    private val debitRepository = mockk<DebitRepository> {
        every { observeLatestKnownDebits(any()) } returns latestDebits
    }
    private val descendedIds = MutableStateFlow<Set<Int>>(emptySet())
    private val logbookRepository = mockk<LogbookRepository> { every { observeDescendedCanyonIds() } returns descendedIds }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `the to-do filter keeps the favorites never descended and marks the others done`() = runTest {
        every { favoritesRepository.getFavorites() } returns MutableStateFlow(listOf(summary(7), summary(8), summary(9)))
        descendedIds.value = setOf(8)
        val viewModel = FavoritesViewModel(favoritesRepository, debitRepository, logbookRepository)
        advanceUntilIdle()
        assertEquals(setOf(8), viewModel.uiState.value.descendedIds)

        viewModel.toggleToDoOnly()
        advanceUntilIdle()
        assertEquals(listOf(7, 9), viewModel.uiState.value.favorites.map { it.id })
        assertEquals(3, viewModel.uiState.value.totalCount)

        descendedIds.value = setOf(8, 9)
        advanceUntilIdle()
        assertEquals(listOf(7), viewModel.uiState.value.favorites.map { it.id })

        viewModel.toggleToDoOnly()
        advanceUntilIdle()
        assertEquals(listOf(7, 8, 9), viewModel.uiState.value.favorites.map { it.id })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `favorites flow populates ui state`() = runTest {
        every { favoritesRepository.getFavorites() } returns MutableStateFlow(listOf(summary(7)))

        val viewModel = FavoritesViewModel(favoritesRepository, debitRepository, logbookRepository)
        advanceUntilIdle()

        assertEquals(listOf(7), viewModel.uiState.value.favorites.map { it.id })
        assertTrue(!viewModel.uiState.value.isLoading)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `favorites show their last known report`() = runTest {
        every { favoritesRepository.getFavorites() } returns MutableStateFlow(listOf(summary(7), summary(8)))
        val viewModel = FavoritesViewModel(favoritesRepository, debitRepository, logbookRepository)

        latestDebits.value = mapOf(7 to Debit(canyonId = 7, date = LocalDate.of(2026, 10, 14), niveau = NiveauDebit.SEC))
        advanceUntilIdle()

        val favorites = viewModel.uiState.value.favorites
        assertEquals(NiveauDebit.SEC, favorites[0].dernierDebit)
        assertEquals(LocalDate.of(2026, 10, 14), favorites[0].dernierDebitDate)
        assertNull(favorites[1].dernierDebit)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `removed favorite can be restored`() = runTest {
        every { favoritesRepository.getFavorites() } returns MutableStateFlow(listOf(summary(7)))
        coEvery { favoritesRepository.removeFavorite(7) } returns Unit
        val viewModel = FavoritesViewModel(favoritesRepository, debitRepository, logbookRepository)
        advanceUntilIdle()

        viewModel.removeFavorite(summary(7))
        advanceUntilIdle()
        assertEquals(7, viewModel.uiState.value.removedFavorite?.id)

        viewModel.undoRemoveFavorite()
        advanceUntilIdle()

        coVerify(exactly = 1) { favoritesRepository.removeFavorite(7) }
        coVerify(exactly = 1) { favoritesRepository.addFavorite(7) }
        assertNull(viewModel.uiState.value.removedFavorite)
    }

    private fun summary(id: Int) = CanyonSummary(
        id = id,
        nom = "Canyon $id",
        pays = "France",
        cotation = "v3a3III",
        url = "/canyoning/canyon/$id/test.html",
    )
}
