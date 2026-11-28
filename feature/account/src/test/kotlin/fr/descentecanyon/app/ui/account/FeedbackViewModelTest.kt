package fr.descentecanyon.app.ui.account

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.AppException
import fr.descentecanyon.app.domain.model.FeedbackContact
import fr.descentecanyon.app.domain.model.FeedbackDraft
import fr.descentecanyon.app.domain.model.FeedbackKind
import fr.descentecanyon.app.domain.model.FeedbackRefusal
import fr.descentecanyon.app.domain.model.FeedbackSendResult
import fr.descentecanyon.app.domain.model.FeedbackTechnicalInfo
import fr.descentecanyon.app.domain.repository.FeedbackRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import fr.descentecanyon.app.ui.common.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val technical = FeedbackTechnicalInfo("3.2.2", 30202, "14 (API 34)", "Google Pixel 5", "fr-FR")
    private val sent = slot<FeedbackDraft>()
    private val repository = mockk<FeedbackRepository>(relaxed = true) {
        every { observeAvailable() } returns flowOf(true)
        every { observeMine() } returns flowOf(emptyList())
        every { technicalInfo() } returns technical
        coEvery { contact() } returns FeedbackContact("Membre", "membre@example.org")
        coEvery { refreshMine() } returns Result.success(emptyList())
        coEvery { send(capture(sent)) } returns Result.success(FeedbackSendResult.SENT)
    }

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) = FeedbackViewModel(handle, repository)

    @Test
    fun `the technical details and the account go with the message by default`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectKind(FeedbackKind.BUG)
        viewModel.onTextChanged("La carte reste blanche au retour.")

        viewModel.send()
        advanceUntilIdle()

        assertEquals(
            FeedbackDraft(
                FeedbackKind.BUG,
                "La carte reste blanche au retour.",
                technical,
                FeedbackContact("Membre", "membre@example.org"),
            ),
            sent.captured,
        )
        val state = viewModel.uiState.value
        assertEquals("", state.text)
        assertNull(state.kind)
        assertEquals(UiText.res(R.string.feedback_sent), state.message)
        // The answers on screen are no longer new.
        coVerify { repository.markRepliesSeen() }
    }

    @Test
    fun `unticked, neither the details nor the account leave the phone`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectKind(FeedbackKind.IDEA)
        viewModel.onTextChanged("Afficher la météo de la semaine.")
        viewModel.setAttachTechnical(false)
        viewModel.setAttachContact(false)

        viewModel.send()
        advanceUntilIdle()

        assertNull(sent.captured.technical)
        assertNull(sent.captured.contact)
    }

    @Test
    fun `a message needs a kind and enough text, and keeps them when refused`() = runTest {
        coEvery { repository.send(any()) } returns Result.failure(AppException(AppError.FeedbackRefused(FeedbackRefusal.RATE_LIMITED)))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onTextChanged("Assez long pour partir.")
        assertFalse(viewModel.uiState.value.canSend)
        viewModel.selectKind(FeedbackKind.CHANGE)
        assertTrue(viewModel.uiState.value.canSend)
        viewModel.onTextChanged("court")
        assertFalse(viewModel.uiState.value.canSend)
        viewModel.onTextChanged("Assez long pour partir.")

        viewModel.send()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Assez long pour partir.", state.text)
        assertEquals(UiText.res(R.string.feedback_error_rate_limited), state.message)
        assertFalse(state.isSending)
    }

    @Test
    fun `without network the person is told the message will leave later`() = runTest {
        coEvery { repository.send(any()) } returns Result.success(FeedbackSendResult.QUEUED)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectKind(FeedbackKind.BUG)
        viewModel.onTextChanged("Le bouton ne répond pas.")

        viewModel.send()
        advanceUntilIdle()

        assertEquals(UiText.res(R.string.feedback_queued), viewModel.uiState.value.message)
    }

    @Test
    fun `logged out, the message is anonymous and the draft survives the process`() = runTest {
        coEvery { repository.contact() } returns null
        val handle = SavedStateHandle()
        val first = viewModel(handle)
        advanceUntilIdle()
        first.selectKind(FeedbackKind.IDEA)
        first.onTextChanged("Une idée en cours d'écriture")

        val restored = viewModel(handle)
        advanceUntilIdle()

        assertNull(restored.uiState.value.contact)
        assertFalse(restored.uiState.value.isLoadingContact)
        assertEquals(FeedbackKind.IDEA, restored.uiState.value.kind)
        assertEquals("Une idée en cours d'écriture", restored.uiState.value.text)
    }
}
