package fr.descentecanyon.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import fr.descentecanyon.app.domain.model.TrackedActivityType
import kotlinx.coroutines.flow.first

class ForumNotificationSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            NotificationWorkerEntryPoint::class.java,
        )
        val repository = entryPoint.notificationCenterRepository()
        val state = repository.observeState().first()
        if (state.forumTargetCount == 0) return Result.success()

        val topics = entryPoint.forumRepository().refreshActiveTopics(MAX_TOPICS_TO_SCAN)
            .getOrElse { return Result.retry() }
            .items
        repository.syncFetchedForumTopics(topics)
        val userPosts = state.followedUsers.mapNotNull { followed ->
            followed.forumUserId?.let { authorId ->
                entryPoint.forumRepository().refreshUserPosts(authorId).getOrNull()
            }
        }.flatten()
        if (userPosts.isNotEmpty() || state.followedUsers.any { it.forumUserId != null }) {
            repository.syncFetchedUserPosts(userPosts)
        }
        val pendingEvents = repository.pendingEvents(TrackedActivityType.FORUM)
        if (entryPoint.appNotificationPublisher().publishForumEvents(pendingEvents)) {
            repository.markEventsDelivered(pendingEvents.map { it.id })
        }
        return Result.success()
    }

    private companion object {
        const val MAX_TOPICS_TO_SCAN = 120
    }
}
