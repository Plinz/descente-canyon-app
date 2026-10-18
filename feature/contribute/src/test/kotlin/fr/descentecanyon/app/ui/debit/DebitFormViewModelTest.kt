package fr.descentecanyon.app.ui.debit

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.ContributorIdentity
import fr.descentecanyon.app.domain.model.DebitSubmissionStatus
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.model.WaterTemperature
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.domain.usecase.SubmitDebitUseCase
import fr.descentecanyon.app.testutil.FakePreferencesRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import fr.descentecanyon.app.ui.common.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
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
    private val canyonRepository = mockk<CanyonRepository> {
        coEvery { getCanyonSummaries(any()) } returns listOf(
            CanyonSummary(id = 2186, nom = "Riolan", pays = "France", departement = "Alpes-Maritimes", cotation = "v4a4III", url = ""),
        )
    }

    private fun createViewModel(savedStateHandle: SavedStateHandle = SavedStateHandle(mapOf("canyonId" to 2186))) = DebitFormViewModel(
        savedStateHandle = savedStateHandle,
        authRepository = authRepository,
        submitDebitUseCase = submitDebitUseCase,
        debitSubmissionRepository = debitSubmissionRepository,
        preferencesRepository = preferences,
        canyonRepository = canyonRepository,
    )

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `the form names the canyon reported on`() = runTest {
        every { authRepository.authState } returns authStateFlow
        every { debitSubmissionRepository.observePendingCount() } returns flowOf(0)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("Riolan", viewModel.uiState.value.canyonName)
        assertEquals("Alpes-Maritimes · France", viewModel.uiState.value.canyonPlace)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `what was typed survives the app being stopped in the background`() = runTest {
        every { authRepository.authState } returns authStateFlow
        every { debitSubmissionRepository.observePendingCount() } returns flowOf(0)
        val savedState = SavedStateHandle(mapOf("canyonId" to 2186))
        val first = createViewModel(savedState)
        first.onObservationDateSelected(LocalDate.of(2026, 10, 17))
        first.onObservationTypeChanged(ObservationType.PARCOURU)
        first.onDebitLevelChanged(NiveauDebit.GROS)
        first.onWaterTemperatureChanged(WaterTemperature.FROIDE)
        first.onCommentChanged("Rappel de 25 m arrosé")
        advanceUntilIdle()

        val restored = createViewModel(savedState).uiState.value

        assertEquals(LocalDate.of(2026, 10, 17), restored.observationDate)
        assertEquals(ObservationType.PARCOURU, restored.observationType)
        assertEquals(NiveauDebit.GROS, restored.debitLevel)
        assertEquals(WaterTemperature.FROIDE, restored.waterTemperature)
        assertEquals("Rappel de 25 m arrosé", restored.comment)
    }

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
    fun `an anonymous report with an invalid e-mail is not sent`() = runTest {
        every { authRepository.authState } returns authStateFlow
        every { debitSubmissionRepository.observePendingCount() } returns flowOf(0)
        val viewModel = createViewModel()
        viewModel.onObserverNameChanged("Antoine")
        viewModel.onObserverEmailChanged("antoine@example")
        viewModel.onObservationTypeChanged(ObservationType.PARCOURU)
        viewModel.onDebitLevelChanged(NiveauDebit.CORRECT)

        viewModel.submit()
        advanceUntilIdle()

        assertEquals(UiText.res(R.string.debit_observer_email_invalid), viewModel.uiState.value.error)
        coVerify(exactly = 0) { debitSubmissionRepository.submit(any()) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `a second tap while sending does not send the report twice`() = runTest {
        every { authRepository.authState } returns authStateFlow
        every { debitSubmissionRepository.observePendingCount() } returns flowOf(0)
        coEvery { debitSubmissionRepository.submit(any()) } returns Result.success(DebitSubmissionStatus.SUBMITTED)
        val viewModel = createViewModel()
        viewModel.onObserverNameChanged("Antoine")
        viewModel.onObserverEmailChanged("antoine@example.com")
        viewModel.onObservationTypeChanged(ObservationType.PARCOURU)
        viewModel.onDebitLevelChanged(NiveauDebit.CORRECT)

        viewModel.submit()
        viewModel.submit()
        advanceUntilIdle()

        coVerify(exactly = 1) { debitSubmissionRepository.submit(any()) }
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
