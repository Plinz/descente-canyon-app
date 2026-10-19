package fr.descentecanyon.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.ForumActiveTopic
import fr.descentecanyon.app.domain.model.ForumUser
import fr.descentecanyon.app.domain.model.ForumUserPost
import fr.descentecanyon.app.domain.model.NotificationCenterState
import fr.descentecanyon.app.domain.model.NotificationSyncSummary
import fr.descentecanyon.app.domain.model.TrackedActivityEvent
import fr.descentecanyon.app.domain.model.TrackedActivityType
import fr.descentecanyon.app.domain.model.forumCategoryKey
import fr.descentecanyon.app.domain.repository.NotificationCenterRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.notificationCenterDataStore by preferencesDataStore(name = "notification_center")

@Singleton
class NotificationCenterRepositoryImpl @Inject constructor(@param:ApplicationContext private val context: Context) :
    NotificationCenterRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun observeState(): Flow<NotificationCenterState> = context.notificationCenterDataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map(::decodeState)
        .distinctUntilChanged()

    override fun observeIsCanyonFollowed(canyonId: Int): Flow<Boolean> = observeState()
        .map { state -> state.followedCanyons.any { it.canyonId == canyonId } }
        .distinctUntilChanged()

    override fun observeIsForumCategoryFollowed(forumId: Int?, forumName: String): Flow<Boolean> {
        val key = forumCategoryKey(forumId, forumName)
        return observeState()
            .map { state -> state.followedForumCategories.any { it.key == key } }
            .distinctUntilChanged()
    }

    override fun observeIsForumThreadFollowed(topicId: Int): Flow<Boolean> = observeState()
        .map { state -> state.followedForumThreads.any { it.topicId == topicId } }
        .distinctUntilChanged()

    override fun observeIsUserFollowed(username: String): Flow<Boolean> = observeState()
        .map { state -> state.followedUsers.any { it.hasUsername(username) } }
        .distinctUntilChanged()

    override suspend fun toggleCanyonFollow(canyonId: Int, canyonName: String, baselineDebits: List<Debit>) {
        updateState { state ->
            if (state.followedCanyons.any { it.canyonId == canyonId }) {
                state.copy(followedCanyons = state.followedCanyons.filterNot { it.canyonId == canyonId })
            } else {
                state.copy(
                    followedCanyons = (
                        state.followedCanyons + NotificationSyncEngine.buildInitialCanyonFollow(
                            canyonId = canyonId,
                            canyonName = canyonName,
                            baselineDebits = baselineDebits,
                        )
                        ).sortedBy { it.canyonName.lowercase() },
                )
            }
        }
    }

    override suspend fun removeCanyonFollow(canyonId: Int) {
        updateState { state ->
            state.copy(followedCanyons = state.followedCanyons.filterNot { it.canyonId == canyonId })
        }
    }

    override suspend fun toggleForumCategoryFollow(forumId: Int?, forumName: String, baselineTopics: List<ForumActiveTopic>) {
        val key = forumCategoryKey(forumId, forumName)
        updateState { state ->
            if (state.followedForumCategories.any { it.key == key }) {
                state.copy(followedForumCategories = state.followedForumCategories.filterNot { it.key == key })
            } else {
                state.copy(
                    followedForumCategories = (
                        state.followedForumCategories + NotificationSyncEngine.buildInitialForumFollow(
                            forumId = forumId,
                            forumName = forumName,
                            baselineTopics = baselineTopics,
                        )
                        ).sortedBy { it.forumName.lowercase() },
                )
            }
        }
    }

    override suspend fun removeForumCategoryFollow(key: String) {
        updateState { state ->
            state.copy(followedForumCategories = state.followedForumCategories.filterNot { it.key == key })
        }
    }

    override suspend fun toggleForumThreadFollow(topic: ForumActiveTopic, baselineTopics: List<ForumActiveTopic>) {
        updateState { state ->
            if (state.followedForumThreads.any { it.topicId == topic.topicId }) {
                state.copy(followedForumThreads = state.followedForumThreads.filterNot { it.topicId == topic.topicId })
            } else {
                state.copy(
                    followedForumThreads = (
                        state.followedForumThreads + NotificationSyncEngine.buildInitialForumThreadFollow(
                            topic = topic,
                            baselineTopics = baselineTopics,
                        )
                        ).sortedBy { it.title.lowercase() },
                )
            }
        }
    }

    override suspend fun removeForumThreadFollow(topicId: Int) {
        updateState { state ->
            state.copy(followedForumThreads = state.followedForumThreads.filterNot { it.topicId == topicId })
        }
    }

    override suspend fun toggleUserFollow(user: ForumUser) {
        updateState { state ->
            if (state.followedUsers.any { it.hasUsername(user.username) }) {
                state.copy(followedUsers = state.followedUsers.filterNot { it.hasUsername(user.username) })
            } else {
                state.copy(
                    followedUsers = (state.followedUsers + NotificationSyncEngine.buildInitialUserFollow(user))
                        .sortedBy { it.username.lowercase() },
                )
            }
        }
    }

    override suspend fun removeUserFollow(username: String) {
        updateState { state ->
            state.copy(followedUsers = state.followedUsers.filterNot { it.hasUsername(username) })
        }
    }

    override suspend fun clearRecentActivity() {
        updateState { state -> state.copy(recentEvents = emptyList()) }
    }

    override suspend fun getOrCreateInstallOffsetMinutes(): Int {
        var resolved = 0
        updateState { state ->
            resolved = state.installOffsetMinutes ?: Random.nextInt(0, 30)
            if (state.installOffsetMinutes == resolved) {
                state
            } else {
                state.copy(installOffsetMinutes = resolved)
            }
        }
        return resolved
    }

    override suspend fun syncFetchedContent(
        latestDebits: List<Debit>,
        activeTopics: List<ForumActiveTopic>,
        nowEpochMs: Long,
    ): NotificationSyncSummary {
        var summary = NotificationSyncSummary()
        updateState { state ->
            val (updatedState, syncSummary) = NotificationSyncEngine.applyFetchedContent(
                state = state,
                latestDebits = latestDebits,
                activeTopics = activeTopics,
                nowEpochMs = nowEpochMs,
            )
            summary = syncSummary
            updatedState
        }
        return summary
    }

    override suspend fun syncFetchedDebits(latestDebits: List<Debit>): NotificationSyncSummary =
        syncFetchedContent(latestDebits = latestDebits, activeTopics = emptyList())

    override suspend fun syncFetchedForumTopics(activeTopics: List<ForumActiveTopic>): NotificationSyncSummary =
        syncFetchedContent(latestDebits = emptyList(), activeTopics = activeTopics)

    override suspend fun syncFetchedUserPosts(posts: List<ForumUserPost>): NotificationSyncSummary {
        var summary = NotificationSyncSummary()
        updateState { state ->
            val (updated, syncSummary) = NotificationSyncEngine.applyFetchedUserPosts(
                state = state,
                posts = posts,
                nowEpochMs = System.currentTimeMillis(),
            )
            summary = syncSummary
            updated
        }
        return summary
    }

    override suspend fun pendingEvents(type: TrackedActivityType): List<TrackedActivityEvent> = observeState().first().recentEvents.filter {
        it.type == type && !it.notificationDelivered
    }

    override suspend fun markEventsDelivered(eventIds: Collection<String>) {
        if (eventIds.isEmpty()) return
        updateState { state ->
            state.copy(
                recentEvents = state.recentEvents.map { event ->
                    if (event.id in eventIds) event.copy(notificationDelivered = true) else event
                },
            )
        }
    }

    private suspend fun updateState(transform: (NotificationCenterState) -> NotificationCenterState) {
        context.notificationCenterDataStore.edit { preferences ->
            val raw = preferences[STATE_KEY]
            val current = raw?.let(::decodeOrNull)
            if (raw != null && current == null) {
                // Follows that cannot be read any more (format changed) are set aside, never silently lost.
                preferences[UNREADABLE_STATE_KEY] = raw
            }
            val updated = transform(current ?: NotificationCenterState())
            preferences[STATE_KEY] = json.encodeToString(updated)
        }
    }

    private fun decodeState(preferences: Preferences): NotificationCenterState =
        preferences[STATE_KEY]?.let(::decodeOrNull) ?: NotificationCenterState()

    private fun decodeOrNull(raw: String): NotificationCenterState? = runCatching {
        json.decodeFromString<NotificationCenterState>(raw)
    }.getOrNull()

    private companion object {
        val STATE_KEY = stringPreferencesKey("notification_center.state")
        val UNREADABLE_STATE_KEY = stringPreferencesKey("notification_center.unreadable_state")
    }
}
