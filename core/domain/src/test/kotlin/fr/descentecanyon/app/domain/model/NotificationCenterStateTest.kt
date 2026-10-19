package fr.descentecanyon.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCenterStateTest {

    @Test
    fun `every followed member is a forum target, with or without a known forum id`() {
        val state = NotificationCenterState(
            followedUsers = listOf(
                FollowedUser(username = "Max38", forumUserId = 12),
                // Searched by name.
                FollowedUser(username = "Riri"),
            ),
        )

        // Following only users must still run the forum sync.
        assertEquals(2, state.forumTargetCount)
    }

    @Test
    fun `a followed member is recognised whatever the case of the name`() {
        val user = FollowedUser(username = "Lolo")

        assertTrue(user.hasUsername("lolo"))
        assertFalse(user.hasUsername("Lolo_"))
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
