package fr.descentecanyon.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class NotificationCenterState(
    val installOffsetMinutes: Int? = null,
    val followedCanyons: List<FollowedCanyon> = emptyList(),
    val followedForumCategories: List<FollowedForumCategory> = emptyList(),
    val followedForumThreads: List<FollowedForumThread> = emptyList(),
    val followedUsers: List<FollowedUser> = emptyList(),
    val recentEvents: List<TrackedActivityEvent> = emptyList(),
) {
    fun hasTrackedTargets(): Boolean = followedCanyons.isNotEmpty() || followedForumCategories.isNotEmpty() ||
        followedForumThreads.isNotEmpty() || followedUsers.isNotEmpty()

    /** What the forum sync has to look at: categories, threads and users. */
    val forumTargetCount: Int get() = followedForumCategories.size + followedForumThreads.size + followedUsers.size
}

@Serializable
data class FollowedCanyon(
    val canyonId: Int,
    val canyonName: String,
    val seenDebitKeys: List<String> = emptyList(),
    val hasSeededLatestDebits: Boolean = false,
)

@Serializable
data class FollowedForumCategory(
    val key: String,
    val forumId: Int? = null,
    val forumName: String,
    val seenTopicMarkers: List<String> = emptyList(),
)

@Serializable
data class FollowedForumThread(
    val topicId: Int,
    val title: String,
    val forumId: Int? = null,
    val forumName: String,
    val topicUrl: String,
    val seenTopicMarkers: List<String> = emptyList(),
)

@Serializable
data class ForumCategory(val forumId: Int?, val forumName: String, val forumUrl: String)

@Serializable
data class TrackedActivityEvent(
    val id: String,
    val type: TrackedActivityType,
    val title: String,
    val body: String,
    val occurredAtEpochMs: Long,
    val canyonId: Int? = null,
    val canyonName: String? = null,
    val forumName: String? = null,
    val externalUrl: String? = null,
    val notificationDelivered: Boolean = false,
)

/**
 * A member whose forum posts are followed. Their flow reports cannot be: the site lists them per
 * canyon only, never per author. Without [forumUserId], the posts are searched by [username].
 */
@Serializable
data class FollowedUser(
    /** The account name as the site shows it; the site tells accounts apart ignoring case. */
    val username: String,
    val forumUserId: Int? = null,
    val seenForumPostMarkers: List<String> = emptyList(),
    val hasSeededForumPosts: Boolean = false,
) {
    fun hasUsername(name: String): Boolean = username.equals(name, ignoreCase = true)
}

@Serializable
enum class TrackedActivityType {
    DEBIT,
    FORUM,
}

data class NotificationSyncSummary(
    val newDebitEvents: List<TrackedActivityEvent> = emptyList(),
    val newForumEvents: List<TrackedActivityEvent> = emptyList(),
) {
    val hasUpdates: Boolean = newDebitEvents.isNotEmpty() || newForumEvents.isNotEmpty()
}

fun forumCategoryKey(forumId: Int?, forumName: String): String = forumId?.let {
    "forum:$it"
} ?: "forum-name:${forumName.trim().lowercase()}"
