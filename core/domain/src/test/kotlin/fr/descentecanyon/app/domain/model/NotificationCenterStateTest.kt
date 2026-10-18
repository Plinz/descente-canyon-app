package fr.descentecanyon.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationCenterStateTest {

    @Test
    fun `followed users with a forum account are forum targets, the others are not`() {
        val state = NotificationCenterState(
            followedUsers = listOf(
                FollowedUser(username = "Max38", normalizedUsername = "max38", forumUserId = 12),
                FollowedUser(username = "Riri", normalizedUsername = "riri"),
            ),
        )

        // Following only users must still run the forum sync.
        assertEquals(1, state.forumTargetCount)
    }

    @Test
    fun `categories and threads are forum targets`() {
        val state = NotificationCenterState(
            followedForumCategories = listOf(FollowedForumCategory(key = "forum:1", forumId = 1, forumName = "Canyon")),
            followedForumThreads = listOf(
                FollowedForumThread(topicId = 7, title = "Sujet", forumName = "Canyon", topicUrl = "https://example.org/t7"),
            ),
        )

        assertEquals(2, state.forumTargetCount)
    }
}
