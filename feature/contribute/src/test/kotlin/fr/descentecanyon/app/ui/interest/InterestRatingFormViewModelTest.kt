package fr.descentecanyon.app.ui.interest

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.model.CanyonInterestRating
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.domain.repository.InterestRatingRepository
import fr.descentecanyon.app.domain.usecase.GetCanyonInterestRatingUseCase
import fr.descentecanyon.app.domain.usecase.SubmitCanyonInterestRatingUseCase
import fr.descentecanyon.app.testutil.MainDispatcherRule
import fr.descentecanyon.app.ui.common.UiText
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

class InterestRatingFormViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val authStateFlow = MutableStateFlow<AuthState>(AuthState.Disconnected)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val repository = mockk<InterestRatingRepository>()
    private val getUseCase = GetCanyonInterestRatingUseCase(repository)
    private val submitUseCase = SubmitCanyonInterestRatingUseCase(repository)

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `connected auth loads existing personal rating`() = runTest {
        every { authRepository.authState } returns authStateFlow
        coEvery { repository.get(26) } returns Result.success(CanyonInterestRating(canyonId = 26, personalRating = 2.5f))
        val viewModel = viewModel()

        authStateFlow.value = AuthState.Connected("antoine")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isConnected)
        assertEquals(25, viewModel.uiState.value.ratingTenths)
        assertEquals(2.5f, viewModel.uiState.value.personalRating)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `submit sends rating as tenths`() = runTest {
        every { authRepository.authState } returns authStateFlow
        coEvery { repository.get(26) } returns Result.success(CanyonInterestRating(canyonId = 26))
        coEvery { repository.submit(any()) } returns Result.success(Unit)
        val viewModel = viewModel()

        authStateFlow.value = AuthState.Connected("antoine")
        advanceUntilIdle()
        viewModel.onRatingTenthsChanged(37)
        viewModel.submit()
        advanceUntilIdle()

        coVerify { repository.submit(match { it.canyonId == 26 && it.rating == 3.7f }) }
        assertEquals(UiText.res(R.string.interest_rating_saved), viewModel.uiState.value.transientMessage)
        assertTrue(viewModel.uiState.value.submitted)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `submit while disconnected requests login`() = runTest {
        every { authRepository.authState } returns authStateFlow
        val viewModel = viewModel()

        viewModel.submit()
        advanceUntilIdle()

        assertEquals(
            UiText.res(R.string.interest_rating_login_required),
            viewModel.uiState.value.loginRequiredMessage,
        )
        coVerify(exactly = 0) { repository.submit(any()) }
    }

    private fun viewModel() = InterestRatingFormViewModel(
        savedStateHandle = SavedStateHandle(mapOf("canyonId" to 26)),
        authRepository = authRepository,
        getCanyonInterestRatingUseCase = getUseCase,
        submitCanyonInterestRatingUseCase = submitUseCase,
    )
}
