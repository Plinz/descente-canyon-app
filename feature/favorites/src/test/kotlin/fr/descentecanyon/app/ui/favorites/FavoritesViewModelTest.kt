package fr.descentecanyon.app.ui.favorites

import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.DailyDebitPrediction
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.DebitPredictionPolicy
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.PredictedDebitLevel
import fr.descentecanyon.app.domain.model.RuntimeLookupSource
import fr.descentecanyon.app.domain.model.WeatherLocationSource
import fr.descentecanyon.app.domain.model.WeatherTarget
import fr.descentecanyon.app.domain.repository.DebitPredictionRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
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
    private val estimates = MutableStateFlow<Map<Int, CanyonDebitPredictions>>(emptyMap())
    private val debitPredictionRepository = mockk<DebitPredictionRepository> { every { observeEstimates(any()) } returns estimates }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `each favorite shows today's estimate as it is computed`() = runTest {
        every { favoritesRepository.getFavorites() } returns MutableStateFlow(listOf(summary(7), summary(8)))
        val viewModel = FavoritesViewModel(favoritesRepository, debitRepository, logbookRepository, debitPredictionRepository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.estimates.isEmpty())

        val today = LocalDate.of(2026, 11, 21)
        estimates.value = mapOf(7 to predictions(today, NiveauDebit.CORRECT, NiveauDebit.GROS))
        advanceUntilIdle()

        assertEquals(setOf(7), viewModel.uiState.value.estimates.keys)
        assertEquals(NiveauDebit.CORRECT, viewModel.uiState.value.estimates.getValue(7).ordinalLevel)
        io.mockk.verify { debitPredictionRepository.observeEstimates(listOf(7, 8)) }
    }

    private fun predictions(today: LocalDate, vararg levels: NiveauDebit) = CanyonDebitPredictions(
        target = WeatherTarget(45.0, 6.0, WeatherLocationSource.UNKNOWN),
        timezone = "Europe/Paris",
        fetchedAt = Instant.EPOCH,
        lookupSource = RuntimeLookupSource.CANYON,
        usedWeatherCache = false,
        policy = DebitPredictionPolicy.BALANCED,
        predictions = levels.mapIndexed { index, level ->
            DailyDebitPrediction(
                date = today.plusDays(index.toLong()),
                horizonDays = index,
                level = PredictedDebitLevel.MEDIUM,
                probabilities = emptyMap(),
                highThreshold = 0.3,
                ordinalLevel = level,
            )
        },
    )

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `the to-do filter keeps the favorites never descended and marks the others done`() = runTest {
        every { favoritesRepository.getFavorites() } returns MutableStateFlow(listOf(summary(7), summary(8), summary(9)))
        descendedIds.value = setOf(8)
        val viewModel = FavoritesViewModel(favoritesRepository, debitRepository, logbookRepository, debitPredictionRepository)
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

        val viewModel = FavoritesViewModel(favoritesRepository, debitRepository, logbookRepository, debitPredictionRepository)
        advanceUntilIdle()

        assertEquals(listOf(7), viewModel.uiState.value.favorites.map { it.id })
        assertTrue(!viewModel.uiState.value.isLoading)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `favorites show their last known report`() = runTest {
        every { favoritesRepository.getFavorites() } returns MutableStateFlow(listOf(summary(7), summary(8)))
        val viewModel = FavoritesViewModel(favoritesRepository, debitRepository, logbookRepository, debitPredictionRepository)

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
        val viewModel = FavoritesViewModel(favoritesRepository, debitRepository, logbookRepository, debitPredictionRepository)
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
