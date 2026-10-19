package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.FollowedCanyon
import fr.descentecanyon.app.domain.model.FollowedForumCategory
import fr.descentecanyon.app.domain.model.FollowedUser
import fr.descentecanyon.app.domain.model.ForumActiveTopic
import fr.descentecanyon.app.domain.model.ForumUser
import fr.descentecanyon.app.domain.model.ForumUserPost
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.NotificationCenterState
import fr.descentecanyon.app.domain.model.TrackedActivityType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSyncEngineTest {

    @Test
    fun `followed user seeds first forum result then emits only new post`() {
        val user = ForumUser(username = "Eric", forumUserId = 5)
        val state = NotificationCenterState(
            followedUsers = listOf(NotificationSyncEngine.buildInitialUserFollow(user)),
        )
        val baseline = forumPost(100)

        val (seeded, initialSummary) = NotificationSyncEngine.applyFetchedUserPosts(state, listOf(baseline), 1L)
        assertTrue(initialSummary.newForumEvents.isEmpty())
        assertTrue(seeded.followedUsers.first().hasSeededForumPosts)

        val (_, summary) = NotificationSyncEngine.applyFetchedUserPosts(
            seeded,
            listOf(forumPost(101), baseline),
            2L,
        )
        assertEquals(listOf("forum-post:101"), summary.newForumEvents.map { it.id })
    }

    @Test
    fun `a followed member whose posts were not read is seeded by the next read, without announcing them`() {
        val state = NotificationCenterState(followedUsers = listOf(FollowedUser(username = "Eric")))

        val (unread, _) = NotificationSyncEngine.applyFetchedUserPosts(state, emptyList(), 1L)
        assertFalse(unread.followedUsers.first().hasSeededForumPosts)

        val (seeded, summary) = NotificationSyncEngine.applyFetchedUserPosts(unread, listOf(forumPost(100), forumPost(99)), 2L)
        assertTrue(summary.newForumEvents.isEmpty())
        assertTrue(seeded.followedUsers.first().hasSeededForumPosts)
    }

    @Test
    fun `followed canyon only emits unseen debit`() {
        val baselineDebit = debit(date = LocalDate.of(2026, 6, 10), niveau = NiveauDebit.CORRECT)
        val state = NotificationCenterState(
            followedCanyons = listOf(
                NotificationSyncEngine.buildInitialCanyonFollow(
                    canyonId = 42,
                    canyonName = "Riolan",
                    baselineDebits = listOf(baselineDebit),
                ),
            ),
        )

        val (updatedState, summary) = NotificationSyncEngine.applyFetchedContent(
            state = state,
            latestDebits = listOf(
                debit(date = LocalDate.of(2026, 6, 12), niveau = NiveauDebit.GROS),
                baselineDebit,
            ),
            activeTopics = emptyList(),
            nowEpochMs = 123L,
        )

        assertEquals(1, summary.newDebitEvents.size)
        assertTrue(summary.newForumEvents.isEmpty())
        assertEquals(TrackedActivityType.DEBIT, summary.newDebitEvents.first().type)
        assertEquals("Riolan", summary.newDebitEvents.first().title)
        assertEquals(2, updatedState.followedCanyons.first().seenDebitKeys.size)
        assertEquals(true, updatedState.followedCanyons.first().hasSeededLatestDebits)
        assertEquals(1, updatedState.recentEvents.size)
    }

    @Test
    fun `first sync after empty canyon follow seeds without notifying`() {
        val state = NotificationCenterState(
            followedCanyons = listOf(
                NotificationSyncEngine.buildInitialCanyonFollow(
                    canyonId = 42,
                    canyonName = "Riolan",
                    baselineDebits = emptyList(),
                ),
            ),
        )

        val (updatedState, summary) = NotificationSyncEngine.applyFetchedContent(
            state = state,
            latestDebits = listOf(debit(date = LocalDate.of(2026, 6, 12), niveau = NiveauDebit.GROS)),
            activeTopics = emptyList(),
            nowEpochMs = 789L,
        )

        assertTrue(summary.newDebitEvents.isEmpty())
        assertEquals(true, updatedState.followedCanyons.first().hasSeededLatestDebits)
        assertEquals(1, updatedState.followedCanyons.first().seenDebitKeys.size)
    }

    @Test
    fun `followed forum category only emits unseen topic marker`() {
        val existingTopic = topic(
            topicId = 10,
            forumId = 16,
            forumName = "SUISSE",
            lastMessageUrl = "https://www.descente-canyon.com/forums/viewtopic.php?f=16&t=10&p=100#p100",
        )
        val state = NotificationCenterState(
            followedForumCategories = listOf(
                NotificationSyncEngine.buildInitialForumFollow(
                    forumId = 16,
                    forumName = "SUISSE",
                    baselineTopics = listOf(existingTopic),
                ),
            ),
        )

        val (_, summary) = NotificationSyncEngine.applyFetchedContent(
            state = state,
            latestDebits = emptyList(),
            activeTopics = listOf(
                existingTopic,
                topic(
                    topicId = 10,
                    forumId = 16,
                    forumName = "SUISSE",
                    lastMessageUrl = "https://www.descente-canyon.com/forums/viewtopic.php?f=16&t=10&p=101#p101",
                ),
            ),
            nowEpochMs = 456L,
        )

        assertTrue(summary.newDebitEvents.isEmpty())
        assertEquals(1, summary.newForumEvents.size)
        assertEquals(TrackedActivityType.FORUM, summary.newForumEvents.first().type)
        assertEquals("Sujet test", summary.newForumEvents.first().title)
    }

    @Test
    fun `forum thread and category do not duplicate same message`() {
        val trackedTopic = topic(
            topicId = 10,
            forumId = 16,
            forumName = "SUISSE",
            lastMessageUrl = "https://www.descente-canyon.com/forums/viewtopic.php?f=16&t=10&p=100#p100",
        )
        val state = NotificationCenterState(
            followedForumCategories = listOf(
                NotificationSyncEngine.buildInitialForumFollow(
                    forumId = 16,
                    forumName = "SUISSE",
                    baselineTopics = listOf(trackedTopic),
                ),
            ),
            followedForumThreads = listOf(
                NotificationSyncEngine.buildInitialForumThreadFollow(
                    topic = trackedTopic,
                    baselineTopics = listOf(trackedTopic),
                ),
            ),
        )

        val (_, summary) = NotificationSyncEngine.applyFetchedContent(
            state = state,
            latestDebits = emptyList(),
            activeTopics = listOf(
                topic(
                    topicId = 10,
                    forumId = 16,
                    forumName = "SUISSE",
                    lastMessageUrl = "https://www.descente-canyon.com/forums/viewtopic.php?f=16&t=10&p=101#p101",
                ),
            ),
            nowEpochMs = 999L,
        )

        assertEquals(1, summary.newForumEvents.size)
    }

    private fun forumPost(postId: Int) = ForumUserPost(
        postId = postId,
        topicId = 10,
        topicTitle = "Sujet",
        author = "Eric",
        postedAtText = "maintenant",
        postUrl = "https://www.descente-canyon.com/forums/viewtopic.php?t=10&p=$postId#p$postId",
    )

    @Test
    fun `following from the canyon page does not notify the report already shown there`() {
        // The canyon page gives the author, temperatures and comment; the latest-debits page does not.
        val shownOnPage = debit(date = LocalDate.of(2026, 10, 18), niveau = NiveauDebit.CORRECT, commentaire = "RAS")
            .copy(isDescended = true, waterTemperature = "Douce", airTemperature = "Bon")
        val fromLatestDebits = Debit(canyonId = 42, canyonNom = "Riolan", date = LocalDate.of(2026, 10, 18), niveau = NiveauDebit.CORRECT)
        val state = NotificationCenterState(
            followedCanyons = listOf(NotificationSyncEngine.buildInitialCanyonFollow(42, "Riolan", listOf(shownOnPage))),
        )

        val (_, summary) = NotificationSyncEngine.applyFetchedContent(state, listOf(fromLatestDebits), emptyList(), nowEpochMs = 1L)

        assertTrue(summary.newDebitEvents.isEmpty())
    }

    @Test
    fun `following a canyon with many reports keeps its latest one as seen`() {
        // The canyon page lists the newest report first, followed by years of older ones.
        val reports = listOf(debit(date = LocalDate.of(2026, 10, 18), niveau = NiveauDebit.CORRECT)) +
            (1..80).map { day -> debit(date = LocalDate.of(2015, 1, 1).plusDays(day.toLong()), niveau = NiveauDebit.GROS) }.reversed()
        val state = NotificationCenterState(
            followedCanyons = listOf(NotificationSyncEngine.buildInitialCanyonFollow(42, "Riolan", reports)),
        )
        val fromLatestDebits = Debit(canyonId = 42, date = LocalDate.of(2026, 10, 18), niveau = NiveauDebit.CORRECT)

        val (_, summary) = NotificationSyncEngine.applyFetchedContent(state, listOf(fromLatestDebits), emptyList(), nowEpochMs = 1L)

        assertTrue(summary.newDebitEvents.isEmpty())
    }

    @Test
    fun `reports seen with the keys of older versions stay seen`() {
        val state = NotificationCenterState(
            followedCanyons = listOf(
                FollowedCanyon(
                    canyonId = 42,
                    canyonName = "Riolan",
                    seenDebitKeys = listOf("42|2026-10-18|CORRECT|antoine|true|Douce|Bon|RAS"),
                    hasSeededLatestDebits = true,
                ),
            ),
        )
        val fromLatestDebits = Debit(canyonId = 42, date = LocalDate.of(2026, 10, 18), niveau = NiveauDebit.CORRECT)

        val (updated, summary) = NotificationSyncEngine.applyFetchedContent(state, listOf(fromLatestDebits), emptyList(), nowEpochMs = 1L)

        assertTrue(summary.newDebitEvents.isEmpty())
        assertEquals(listOf("42|2026-10-18|CORRECT"), updated.followedCanyons.single().seenDebitKeys)
    }

    @Test
    fun `another level reported the same day is notified`() {
        val state = NotificationCenterState(
            followedCanyons = listOf(
                NotificationSyncEngine.buildInitialCanyonFollow(
                    canyonId = 42,
                    canyonName = "Riolan",
                    baselineDebits = listOf(debit(date = LocalDate.of(2026, 10, 18), niveau = NiveauDebit.CORRECT)),
                ),
            ),
        )

        val (_, summary) = NotificationSyncEngine.applyFetchedContent(
            state,
            listOf(debit(date = LocalDate.of(2026, 10, 18), niveau = NiveauDebit.GROS)),
            emptyList(),
            nowEpochMs = 1L,
        )

        assertEquals(1, summary.newDebitEvents.size)
    }

    @Test
    fun `markers still listed are never the ones dropped from a full history`() {
        val listed = topic(topicId = 7, forumId = 1, forumName = "Canyon", lastMessageUrl = "https://example.org/p7")
        val fresh = topic(topicId = 8, forumId = 1, forumName = "Canyon", lastMessageUrl = "https://example.org/p8")
        // 50 markers seen, the one of the topic still listed being the oldest.
        val seen = listOf(NotificationSyncEngine.buildForumTopicMarker(listed)) + (2..50).map { "old-$it" }
        val state = NotificationCenterState(
            followedForumCategories = listOf(
                FollowedForumCategory(key = "forum:1", forumId = 1, forumName = "Canyon", seenTopicMarkers = seen),
            ),
        )

        val (updated, first) = NotificationSyncEngine.applyFetchedContent(state, emptyList(), listOf(listed, fresh), nowEpochMs = 1L)
        val (_, second) = NotificationSyncEngine.applyFetchedContent(updated, emptyList(), listOf(listed, fresh), nowEpochMs = 2L)

        assertEquals(listOf("forum:${NotificationSyncEngine.buildForumTopicMarker(fresh)}"), first.newForumEvents.map { it.id })
        assertTrue(second.newForumEvents.isEmpty())
    }

    @Test
    fun `event identifiers do not depend on sync time`() {
        val baseline = debit(date = LocalDate.of(2026, 6, 10), niveau = NiveauDebit.CORRECT)
        val state = NotificationCenterState(
            followedCanyons = listOf(
                NotificationSyncEngine.buildInitialCanyonFollow(42, "Riolan", listOf(baseline)),
            ),
        )
        val newDebit = debit(date = LocalDate.of(2026, 6, 12), niveau = NiveauDebit.GROS)

        val (_, firstSummary) = NotificationSyncEngine.applyFetchedContent(
            state,
            listOf(newDebit),
            emptyList(),
            nowEpochMs = 1L,
        )
        val (_, secondSummary) = NotificationSyncEngine.applyFetchedContent(
            state,
            listOf(newDebit),
            emptyList(),
            nowEpochMs = 2L,
        )

        assertEquals(firstSummary.newDebitEvents.single().id, secondSummary.newDebitEvents.single().id)
    }

    private fun debit(date: LocalDate, niveau: NiveauDebit, commentaire: String? = null): Debit = Debit(
        canyonId = 42,
        canyonNom = "Riolan",
        date = date,
        niveau = niveau,
        auteur = "antoine",
        commentaire = commentaire,
    )

    private fun topic(topicId: Int, forumId: Int, forumName: String, lastMessageUrl: String): ForumActiveTopic = ForumActiveTopic(
        topicId = topicId,
        title = "Sujet test",
        forumId = forumId,
        forumName = forumName,
        replyCount = 4,
        viewCount = 120,
        lastAuthor = "Max38",
        lastPostedAtText = "ven. 12 juin 2026 10:42",
        lastPostedAtEpochMs = 1_718_184_520_000,
        topicUrl = "https://www.descente-canyon.com/forums/viewtopic.php?f=$forumId&t=$topicId",
        lastMessageUrl = lastMessageUrl,
    )
}
