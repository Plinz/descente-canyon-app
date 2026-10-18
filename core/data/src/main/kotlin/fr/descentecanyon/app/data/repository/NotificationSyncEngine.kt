package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.FollowedCanyon
import fr.descentecanyon.app.domain.model.FollowedForumCategory
import fr.descentecanyon.app.domain.model.FollowedForumThread
import fr.descentecanyon.app.domain.model.FollowedUser
import fr.descentecanyon.app.domain.model.ForumActiveTopic
import fr.descentecanyon.app.domain.model.ForumUser
import fr.descentecanyon.app.domain.model.ForumUserPost
import fr.descentecanyon.app.domain.model.NotificationCenterState
import fr.descentecanyon.app.domain.model.NotificationSyncSummary
import fr.descentecanyon.app.domain.model.TrackedActivityEvent
import fr.descentecanyon.app.domain.model.TrackedActivityType
import fr.descentecanyon.app.domain.model.forumCategoryKey
import java.time.format.DateTimeFormatter

internal object NotificationSyncEngine {

    private const val MAX_SEEN_KEYS = 50
    private const val DEBIT_KEY_PARTS = 3
    private const val MAX_ACTIVITY_EVENTS = 60
    private val debitDateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /**
     * What a followed canyon is compared on: the latest-debits page, which the sync reads, only gives
     * the canyon, the date and the level of its last report. Author, temperatures or comment (known
     * when the follow starts from the canyon page) would make every report shown there look new.
     */
    fun buildDebitKey(debit: Debit): String = listOf(debit.canyonId.toString(), debit.date.toString(), debit.niveau.name).joinToString("|")

    /** Keys stored by versions <= 2.4.1 went on with author, temperatures and comment. */
    private fun String.asDebitKey(): String = split('|').take(DEBIT_KEY_PARTS).joinToString("|")

    fun buildForumTopicMarker(topic: ForumActiveTopic): String = buildString {
        append(topic.topicId)
        append('|')
        append(topic.lastMessageUrl.ifBlank { topic.topicUrl })
    }

    fun buildInitialCanyonFollow(canyonId: Int, canyonName: String, baselineDebits: List<Debit>): FollowedCanyon = FollowedCanyon(
        canyonId = canyonId,
        canyonName = canyonName,
        // Oldest first, like every list of seen keys, so the most recent are the ones kept: the canyon
        // page lists the newest reports first and a popular canyon has hundreds.
        seenDebitKeys = baselineDebits.sortedBy { it.date }.map(::buildDebitKey).distinct().takeLast(MAX_SEEN_KEYS),
        hasSeededLatestDebits = baselineDebits.isNotEmpty(),
    )

    fun buildInitialForumFollow(forumId: Int?, forumName: String, baselineTopics: List<ForumActiveTopic>): FollowedForumCategory =
        FollowedForumCategory(
            key = forumCategoryKey(forumId, forumName),
            forumId = forumId,
            forumName = forumName,
            seenTopicMarkers = baselineTopics
                .filter { topic -> matchesForumCategory(topic, forumId, forumName) }
                .map(::buildForumTopicMarker)
                .distinct()
                .takeLast(MAX_SEEN_KEYS),
        )

    fun buildInitialForumThreadFollow(topic: ForumActiveTopic, baselineTopics: List<ForumActiveTopic>): FollowedForumThread =
        FollowedForumThread(
            topicId = topic.topicId,
            title = topic.title,
            forumId = topic.forumId,
            forumName = topic.forumName,
            topicUrl = topic.topicUrl,
            seenTopicMarkers = baselineTopics
                .filter { it.topicId == topic.topicId }
                .map(::buildForumTopicMarker)
                .distinct()
                .takeLast(MAX_SEEN_KEYS),
        )

    fun buildInitialUserFollow(user: ForumUser): FollowedUser = FollowedUser(
        username = user.username,
        normalizedUsername = user.normalizedUsername,
        forumUserId = user.forumUserId,
    )

    fun matchesForumCategory(topic: ForumActiveTopic, forumId: Int?, forumName: String): Boolean =
        if (forumId != null && topic.forumId != null) {
            topic.forumId == forumId
        } else {
            topic.forumName.equals(forumName, ignoreCase = true)
        }

    fun applyFetchedContent(
        state: NotificationCenterState,
        latestDebits: List<Debit>,
        activeTopics: List<ForumActiveTopic>,
        nowEpochMs: Long,
    ): Pair<NotificationCenterState, NotificationSyncSummary> {
        val debitEvents = mutableListOf<TrackedActivityEvent>()
        val forumEvents = mutableListOf<TrackedActivityEvent>()

        val updatedCanyons = state.followedCanyons.map { followed ->
            val matchingDebits = latestDebits.filter { it.canyonId == followed.canyonId }.distinctBy(::buildDebitKey)
            val matchingKeys = matchingDebits.map(::buildDebitKey)
            val previousKeys = followed.seenDebitKeys.map { it.asDebitKey() }
            val seenKeys = previousKeys.toSet()
            val newDebits = if (followed.hasSeededLatestDebits) {
                matchingDebits.filter { debit -> buildDebitKey(debit) !in seenKeys }
            } else {
                emptyList()
            }
            newDebits.forEach { debit ->
                debitEvents += TrackedActivityEvent(
                    id = "debit:${buildDebitKey(debit)}",
                    type = TrackedActivityType.DEBIT,
                    title = followed.canyonName,
                    body = "${debit.niveau.label} • ${debit.date.format(debitDateFormatter)}",
                    occurredAtEpochMs = nowEpochMs,
                    canyonId = followed.canyonId,
                    canyonName = followed.canyonName,
                )
            }
            followed.copy(
                seenDebitKeys = mergeSeenKeys(
                    previous = previousKeys,
                    current = matchingKeys,
                ),
                hasSeededLatestDebits = true,
            )
        }

        val emittedForumMarkers = linkedSetOf<String>()

        val updatedForumCategories = state.followedForumCategories.map { followed ->
            val matchingTopics = activeTopics.filter { topic ->
                matchesForumCategory(topic, followed.forumId, followed.forumName)
            }
            val matchingMarkers = matchingTopics.map(::buildForumTopicMarker)
            val seenMarkers = followed.seenTopicMarkers.toSet()
            val newTopics = matchingTopics.filter { topic -> buildForumTopicMarker(topic) !in seenMarkers }
            newTopics.forEach { topic ->
                val marker = buildForumTopicMarker(topic)
                if (emittedForumMarkers.add(marker)) {
                    forumEvents += TrackedActivityEvent(
                        id = "forum:$marker",
                        type = TrackedActivityType.FORUM,
                        title = topic.title,
                        body = topic.lastAuthor
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "$it • ${topic.lastPostedAtText}" }
                            ?: topic.lastPostedAtText,
                        occurredAtEpochMs = nowEpochMs,
                        forumName = followed.forumName,
                        externalUrl = topic.lastMessageUrl.ifBlank { topic.topicUrl },
                    )
                }
            }
            followed.copy(
                seenTopicMarkers = mergeSeenKeys(
                    previous = followed.seenTopicMarkers,
                    current = matchingMarkers,
                ),
            )
        }

        val updatedForumThreads = state.followedForumThreads.map { followed ->
            val matchingTopics = activeTopics.filter { topic -> topic.topicId == followed.topicId }
            val matchingMarkers = matchingTopics.map(::buildForumTopicMarker)
            val seenMarkers = followed.seenTopicMarkers.toSet()
            val newTopics = matchingTopics.filter { topic -> buildForumTopicMarker(topic) !in seenMarkers }
            newTopics.forEach { topic ->
                val marker = buildForumTopicMarker(topic)
                if (emittedForumMarkers.add(marker)) {
                    forumEvents += TrackedActivityEvent(
                        id = "forum:$marker",
                        type = TrackedActivityType.FORUM,
                        title = topic.title,
                        body = topic.lastAuthor
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "$it • ${topic.lastPostedAtText}" }
                            ?: topic.lastPostedAtText,
                        occurredAtEpochMs = nowEpochMs,
                        forumName = followed.forumName,
                        externalUrl = topic.lastMessageUrl.ifBlank { topic.topicUrl },
                    )
                }
            }
            followed.copy(
                seenTopicMarkers = mergeSeenKeys(
                    previous = followed.seenTopicMarkers,
                    current = matchingMarkers,
                ),
            )
        }

        val updatedState = state.copy(
            followedCanyons = updatedCanyons,
            followedForumCategories = updatedForumCategories,
            followedForumThreads = updatedForumThreads,
            recentEvents = (debitEvents + forumEvents + state.recentEvents)
                .sortedByDescending { it.occurredAtEpochMs }
                .distinctBy { it.id }
                .take(MAX_ACTIVITY_EVENTS),
        )
        val summary = NotificationSyncSummary(
            newDebitEvents = debitEvents,
            newForumEvents = forumEvents,
        )
        return updatedState to summary
    }

    fun applyFetchedUserPosts(
        state: NotificationCenterState,
        posts: List<ForumUserPost>,
        nowEpochMs: Long,
    ): Pair<NotificationCenterState, NotificationSyncSummary> {
        val events = mutableListOf<TrackedActivityEvent>()
        val users = state.followedUsers.map { followed ->
            val matching = posts.filter { it.author.equals(followed.username, ignoreCase = true) }
            val markers = matching.map { "forum-post:${it.postId}" }
            if (followed.hasSeededForumPosts) {
                matching.filter { "forum-post:${it.postId}" !in followed.seenForumPostMarkers }.forEach { post ->
                    events += TrackedActivityEvent(
                        id = "forum-post:${post.postId}",
                        type = TrackedActivityType.FORUM,
                        title = post.topicTitle,
                        body = "${followed.username} • ${post.postedAtText}",
                        occurredAtEpochMs = nowEpochMs,
                        externalUrl = post.postUrl,
                    )
                }
            }
            followed.copy(
                seenForumPostMarkers = mergeSeenKeys(followed.seenForumPostMarkers, markers),
                hasSeededForumPosts = true,
            )
        }
        val updated = state.copy(
            followedUsers = users,
            recentEvents = (events + state.recentEvents).sortedByDescending { it.occurredAtEpochMs }
                .distinctBy { it.id }.take(MAX_ACTIVITY_EVENTS),
        )
        return updated to NotificationSyncSummary(newForumEvents = events)
    }

    /** The keys still on the page are kept last, so the oldest dropped are never ones that would come back as new. */
    private fun mergeSeenKeys(previous: List<String>, current: List<String>): List<String> {
        val currentKeys = current.toSet()
        return (previous.filterNot { it in currentKeys } + current)
            .distinct()
            .takeLast(MAX_SEEN_KEYS)
    }
}
