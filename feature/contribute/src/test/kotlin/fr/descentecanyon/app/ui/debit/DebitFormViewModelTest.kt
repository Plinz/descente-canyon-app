package fr.descentecanyon.app.ui.debit

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.model.ContributorIdentity
import fr.descentecanyon.app.domain.model.DebitSubmissionStatus
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.domain.usecase.SubmitDebitUseCase
import fr.descentecanyon.app.testutil.FakePreferencesRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import fr.descentecanyon.app.ui.common.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DebitFormViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val authStateFlow = MutableStateFlow<AuthState>(AuthState.Disconnected)
    private val authRepository = mockk<AuthRepository>()
    private val debitSubmissionRepository = mockk<DebitSubmissionRepository>()
    private val submitDebitUseCase = SubmitDebitUseCase(debitSubmissionRepository)
    private val preferences = FakePreferencesRepository()

    private fun createViewModel() = DebitFormViewModel(
        savedStateHandle = SavedStateHandle(mapOf("canyonId" to 2186)),
        authRepository = authRepository,
        submitDebitUseCase = submitDebitUseCase,
        debitSubmissionRepository = debitSubmissionRepository,
        preferencesRepository = preferences,
    )

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `connected auth pre-fills observer name`() = runTest {
        every { authRepository.authState } returns authStateFlow
        every { debitSubmissionRepository.observePendingCount() } returns flowOf(0)
        val viewModel = createViewModel()

        authStateFlow.value = AuthState.Connected("antoine")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isConnected)
        assertEquals("antoine", viewModel.uiState.value.observerName)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `submit emits queued offline message`() = runTest {
        every { authRepository.authState } returns authStateFlow
        every { debitSubmissionRepository.observePendingCount() } returns flowOf(1)
        coEvery { debitSubmissionRepository.submit(any()) } returns Result.success(DebitSubmissionStatus.QUEUED_OFFLINE)
        val viewModel = createViewModel()

        viewModel.onObserverNameChanged("Antoine")
        viewModel.onObserverEmailChanged("antoine@example.com")
        viewModel.onObservationTypeChanged(ObservationType.PARCOURU)
        viewModel.onDebitLevelChanged(NiveauDebit.CORRECT)
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(UiText.res(R.string.debit_submission_saved_offline), viewModel.uiState.value.transientMessage)
        assertEquals(DebitSubmissionStatus.QUEUED_OFFLINE, viewModel.uiState.value.lastSubmissionStatus)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `submit requires explicit observation type and debit`() = runTest {
        every { authRepository.authState } returns authStateFlow
        every { debitSubmissionRepository.observePendingCount() } returns flowOf(0)
        coEvery { debitSubmissionRepository.submit(any()) } returns Result.success(DebitSubmissionStatus.SUBMITTED)
        val viewModel = createViewModel()

        viewModel.onObserverNameChanged("Antoine")
        viewModel.onObserverEmailChanged("antoine@example.com")
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(UiText.res(R.string.debit_observation_type_required), viewModel.uiState.value.error)

        viewModel.onObservationTypeChanged(ObservationType.PARCOURU)
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(UiText.res(R.string.debit_level_required), viewModel.uiState.value.error)
        coVerify(exactly = 0) { debitSubmissionRepository.submit(any()) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `anonymous form is prefilled with the identity of the last report`() = runTest {
        every { authRepository.authState } returns authStateFlow
        every { debitSubmissionRepository.observePendingCount() } returns flowOf(0)
        preferences.contributorIdentity = ContributorIdentity("Antoine", "antoine@example.com")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("Antoine", viewModel.uiState.value.observerName)
        assertEquals("antoine@example.com", viewModel.uiState.value.observerEmail)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `logged in user keeps the account name instead of the remembered identity`() = runTest {
        every { authRepository.authState } returns authStateFlow
        every { debitSubmissionRepository.observePendingCount() } returns flowOf(0)
        preferences.contributorIdentity = ContributorIdentity("Antoine", "antoine@example.com")
        authStateFlow.value = AuthState.Connected("plinz")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("plinz", viewModel.uiState.value.observerName)
        assertEquals("", viewModel.uiState.value.observerEmail)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `anonymous report remembers the identity for the next one`() = runTest {
        every { authRepository.authState } returns authStateFlow
        every { debitSubmissionRepository.observePendingCount() } returns flowOf(0)
        coEvery { debitSubmissionRepository.submit(any()) } returns Result.success(DebitSubmissionStatus.SUBMITTED)
        val viewModel = createViewModel()

        viewModel.onObserverNameChanged(" Antoine ")
        viewModel.onObserverEmailChanged("antoine@example.com")
        viewModel.onObservationTypeChanged(ObservationType.PARCOURU)
        viewModel.onDebitLevelChanged(NiveauDebit.CORRECT)
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(ContributorIdentity("Antoine", "antoine@example.com"), preferences.contributorIdentity)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `failed report does not change the remembered identity`() = runTest {
        every { authRepository.authState } returns authStateFlow
        every { debitSubmissionRepository.observePendingCount() } returns flowOf(0)
        coEvery { debitSubmissionRepository.submit(any()) } returns Result.failure(IllegalStateException("boom"))
        val viewModel = createViewModel()

        viewModel.onObserverNameChanged("Antoine")
        viewModel.onObserverEmailChanged("antoine@example.com")
        viewModel.onObservationTypeChanged(ObservationType.PARCOURU)
        viewModel.onDebitLevelChanged(NiveauDebit.CORRECT)
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(null, preferences.contributorIdentity)
    }
}
