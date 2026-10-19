package fr.descentecanyon.app.ui.users

import fr.descentecanyon.app.domain.model.FollowedUser
import fr.descentecanyon.app.domain.model.ForumUser
import fr.descentecanyon.app.domain.model.NotificationCenterState
import fr.descentecanyon.app.domain.repository.ForumUserRepository
import fr.descentecanyon.app.domain.repository.NotificationCenterRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserSearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val forumUserRepository = mockk<ForumUserRepository>()
    private val notificationCenterRepository = mockk<NotificationCenterRepository> {
        every { observeState() } returns flowOf(NotificationCenterState())
    }

    @Test
    fun `the text typed shows at once, before the search runs`() = runTest {
        coEvery { forumUserRepository.search(any(), any()) } returns emptyList()
        val viewModel = UserSearchViewModel(forumUserRepository, notificationCenterRepository)

        viewModel.updateQuery("r")
        viewModel.updateQuery("ri")
        viewModel.updateQuery("rio")
        advanceTimeBy(150)

        assertEquals("rio", viewModel.uiState.value.query)
        assertTrue(viewModel.uiState.value.isSearching)
        coVerify(exactly = 0) { forumUserRepository.search(any(), any()) }
    }

    @Test
    fun `users are searched once typing pauses`() = runTest {
        val riou = ForumUser(username = "Riou")
        coEvery { forumUserRepository.search("rio", any()) } returns listOf(riou)
        val viewModel = UserSearchViewModel(forumUserRepository, notificationCenterRepository)

        viewModel.updateQuery("ri")
        advanceTimeBy(100)
        viewModel.updateQuery("rio")
        advanceUntilIdle()

        assertEquals("rio", viewModel.uiState.value.query)
        assertEquals(listOf(riou), viewModel.uiState.value.users)
        assertFalse(viewModel.uiState.value.isSearching)
        coVerify(exactly = 1) { forumUserRepository.search(any(), any()) }
    }

    @Test
    fun `a followed member shows as followed whatever the case of the stored name`() = runTest {
        every { notificationCenterRepository.observeState() } returns flowOf(
            NotificationCenterState(followedUsers = listOf(FollowedUser(username = "LOLO"))),
        )
        val viewModel = UserSearchViewModel(forumUserRepository, notificationCenterRepository)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isFollowed(ForumUser(username = "lolo")))
        assertFalse(viewModel.uiState.value.isFollowed(ForumUser(username = "Lolo_")))
    }

    @Test
    fun `a single letter is not searched`() = runTest {
        val viewModel = UserSearchViewModel(forumUserRepository, notificationCenterRepository)

        viewModel.updateQuery("r")
        advanceUntilIdle()

        assertEquals("r", viewModel.uiState.value.query)
        assertEquals(emptyList<ForumUser>(), viewModel.uiState.value.users)
        coVerify(exactly = 0) { forumUserRepository.search(any(), any()) }
    }
}
