package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitPredictionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebitEstimateRepositoryImplTest {

    private val estimate = mockk<CanyonDebitPredictions>()
    private val located = mockk<CanyonDetail> { every { isLocated } returns true }
    private val unlocated = mockk<CanyonDetail> { every { isLocated } returns false }
    private val canyons = mockk<CanyonRepository> {
        coEvery { getCanyonPreview(1) } returns Result.success(located)
        coEvery { getCanyonPreview(2) } returns Result.success(unlocated)
        coEvery { getCanyonPreview(3) } returns Result.success(located)
    }
    private val predictions = mockk<DebitPredictionRepository> {
        coEvery { getCachedPredictions(any()) } returns null
        coEvery { getCachedPredictions(3) } returns estimate
        coEvery { getPredictions(located) } returns Result.success(estimate)
    }

    private fun repository(online: Boolean = true, dispatcher: TestDispatcher) = DebitEstimateRepositoryImpl(
        canyons,
        predictions,
        mockk<ConnectivityObserver> { every { isCurrentlyOnline() } returns online },
        dispatcher,
    )

    @Test
    fun `a canyon already estimated costs no request, one without position is not estimated`() = runTest {
        val progress = repository(dispatcher = StandardTestDispatcher(testScheduler)).estimate(listOf(1, 2, 3)).last()

        assertEquals(setOf(1, 3), progress.estimates.keys)
        assertEquals(setOf(2), progress.unavailableIds)
        assertTrue(progress.isComplete)
        // Canyon 3 comes from the cache: only canyon 1 goes through the weather and the model.
        coVerify(exactly = 1) { predictions.getPredictions(any()) }
    }

    @Test
    fun `offline only the estimates kept on the device are used`() = runTest {
        val progress = repository(online = false, dispatcher = StandardTestDispatcher(testScheduler)).estimate(listOf(1, 3)).last()

        assertEquals(setOf(3), progress.estimates.keys)
        assertTrue(progress.offlineSkipped)
        assertTrue(progress.isComplete)
        coVerify(exactly = 0) { predictions.getPredictions(any()) }
    }
}
