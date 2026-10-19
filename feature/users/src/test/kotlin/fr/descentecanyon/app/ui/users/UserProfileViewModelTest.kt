package fr.descentecanyon.app.ui.users

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.domain.model.ForumUser
import fr.descentecanyon.app.domain.repository.ForumUserRepository
import fr.descentecanyon.app.domain.repository.NotificationCenterRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
class UserProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val forumUserRepository = mockk<ForumUserRepository>()
    private val notificationCenterRepository = mockk<NotificationCenterRepository> {
        every { observeIsUserFollowed(any()) } returns flowOf(false)
    }

    @Test
    fun `the member is read by the exact name of the route`() = runTest {
        val lolo = ForumUser(username = "Lolo_", debitReportCount = 33)
        coEvery { forumUserRepository.getByUsername("Lolo_") } returns lolo
        every { notificationCenterRepository.observeIsUserFollowed("Lolo_") } returns flowOf(true)

        val viewModel = viewModel("Lolo_")
        advanceUntilIdle()

        assertEquals(lolo, viewModel.uiState.value.user)
        assertTrue(viewModel.uiState.value.isFollowed)
    }

    @Test
    fun `a name that is not a member is reported as not found once read`() = runTest {
        coEvery { forumUserRepository.getByUsername(any()) } returns null

        val viewModel = viewModel("Pierre, Paul et Jacques")
        assertFalse(viewModel.uiState.value.isLoaded)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isLoaded)
        assertNull(viewModel.uiState.value.user)
        assertEquals("Pierre, Paul et Jacques", viewModel.uiState.value.username)
    }

    private fun viewModel(username: String) = UserProfileViewModel(
        SavedStateHandle(mapOf("username" to username)),
        forumUserRepository,
        notificationCenterRepository,
    )
}
