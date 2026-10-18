package fr.descentecanyon.app.ui.pending

import app.cash.turbine.test
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.model.AirTemperature
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.model.PendingDebitRejection
import fr.descentecanyon.app.domain.model.PendingDebitSubmission
import fr.descentecanyon.app.domain.model.WaterTemperature
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PendingDebitsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val pending = MutableStateFlow<List<PendingDebitSubmission>>(emptyList())
    private val online = MutableStateFlow(true)
    private val authState = MutableStateFlow<AuthState>(AuthState.Disconnected)
    private val lastSyncFailure = MutableStateFlow<AppError?>(null)
    private val debitSubmissionRepository = mockk<DebitSubmissionRepository>(relaxUnitFun = true) {
        every { observePending() } returns pending
        every { observeLastSyncFailure() } returns lastSyncFailure
    }
    private val canyonRepository = mockk<CanyonRepository> {
        coEvery { getCanyonSummaries(any()) } returns listOf(summary(2186, "Riolan"))
    }
    private val connectivityObserver = object : ConnectivityObserver {
        override fun observe(): Flow<Boolean> = online
        override fun isCurrentlyOnline(): Boolean = online.value
    }
    private val authRepository = mockk<AuthRepository> {
        every { this@mockk.authState } returns this@PendingDebitsViewModelTest.authState
    }

    private fun createViewModel() =
        PendingDebitsViewModel(debitSubmissionRepository, canyonRepository, connectivityObserver, authRepository)

    @Test
    fun `items carry the canyon name and the offline status`() = runTest {
        pending.value = listOf(pendingSubmission(id = 1, canyonId = 2186, email = "a@example.com"))
        online.value = false
        val viewModel = createViewModel()

        viewModel.uiState.test {
            val state = awaitItemMatching { !it.isLoading }
            assertEquals(listOf("Riolan"), state.items.map { it.canyonName })
            assertEquals(PendingDebitsStatus.OFFLINE, state.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a sending stopped by the site is shown as blocked, a refused report is not sent`() = runTest {
        pending.value = listOf(
            pendingSubmission(id = 1, canyonId = 2186, email = "a@example.com"),
            pendingSubmission(id = 2, canyonId = 2186, email = "a@example.com")
                .copy(rejection = PendingDebitRejection(atEpochMs = 1L, siteMessage = "Date invalide")),
        )
        lastSyncFailure.value = AppError.UnexpectedResponse
        val viewModel = createViewModel()

        viewModel.uiState.test {
            val state = awaitItemMatching { !it.isLoading }
            assertEquals(PendingDebitsStatus.BLOCKED, state.status)
            assertEquals(listOf(false, true), state.items.map { it.isRejected })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `only refused reports leave nothing to send`() {
        val refused = PendingDebitItem(
            id = 1, canyonId = 1, canyonName = null, observationDate = LocalDate.of(2026, 10, 1),
            observationType = ObservationType.PARCOURU, debitLevel = NiveauDebit.SEC, comment = "", needsLogin = false,
            rejection = PendingDebitRejection(atEpochMs = 1L, siteMessage = null),
        )

        assertEquals(PendingDebitsStatus.EMPTY, pendingDebitsStatus(listOf(refused), online = true, loggedIn = true))
    }

    @Test
    fun `resending a refused report puts it back in the queue and sends it`() = runTest {
        coEvery { debitSubmissionRepository.syncPending() } returns fr.descentecanyon.app.domain.model.PendingDebitSyncResult()
        val viewModel = createViewModel()

        viewModel.resend(2)
        advanceUntilIdle()

        coVerify { debitSubmissionRepository.retryPending(2) }
        coVerify { debitSubmissionRepository.syncPending() }
    }

    @Test
    fun `reports written with an account need a login once online`() = runTest {
        pending.value = listOf(pendingSubmission(id = 1, canyonId = 2186, email = null))
        val viewModel = createViewModel()

        viewModel.uiState.test {
            assertEquals(PendingDebitsStatus.LOGIN_REQUIRED, awaitItemMatching { !it.isLoading }.status)
            authState.value = AuthState.Connected("plinz")
            assertEquals(PendingDebitsStatus.SENDING, awaitItem().status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `empty queue has the empty status`() {
        assertEquals(PendingDebitsStatus.EMPTY, pendingDebitsStatus(emptyList(), online = false, loggedIn = false))
    }

    @Test
    fun `delete removes the report from the queue`() = runTest {
        val viewModel = createViewModel()

        viewModel.delete(7)
        advanceUntilIdle()

        coVerify { debitSubmissionRepository.deletePending(7) }
    }

    private suspend fun app.cash.turbine.ReceiveTurbine<PendingDebitsUiState>.awaitItemMatching(
        predicate: (PendingDebitsUiState) -> Boolean,
    ): PendingDebitsUiState {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    private fun pendingSubmission(id: Long, canyonId: Int, email: String?) = PendingDebitSubmission(
        id = id,
        submission = DebitSubmission(
            canyonId = canyonId,
            observerName = "Antoine",
            observerEmail = email,
            observationDate = LocalDate.of(2026, 10, 14),
            observationType = ObservationType.PARCOURU,
            debitLevel = NiveauDebit.CORRECT,
            waterTemperature = WaterTemperature.INCONNUE,
            airTemperature = AirTemperature.INCONNUE,
        ),
        createdAtEpochMs = 0L,
    )

    private fun summary(id: Int, name: String) = CanyonSummary(
        id = id,
        nom = name,
        pays = "France",
        departement = "Alpes-Maritimes",
        cotation = "v3a2II",
        interet = 3f,
        url = "https://www.descente-canyon.com/canyoning/canyon/$id/",
    )
}
