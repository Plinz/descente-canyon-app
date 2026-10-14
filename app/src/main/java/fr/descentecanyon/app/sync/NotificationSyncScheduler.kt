package fr.descentecanyon.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.NotificationCenterState
import fr.descentecanyon.app.domain.repository.NotificationCenterRepository
import fr.descentecanyon.app.notifications.DebitNotificationSyncWorker
import fr.descentecanyon.app.notifications.ForumNotificationSyncWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Singleton
class NotificationSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notificationCenterRepository: NotificationCenterRepository,
    private val connectivityObserver: ConnectivityObserver,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        observeTrackedTargets()
        observeConnectivity()
    }

    private fun observeTrackedTargets() {
        scope.launch {
            var previous = TrackedTargets()
            var hasObservedTargets = false
            notificationCenterRepository.observeState()
                .mapToTrackedTargets()
                .distinctUntilChanged()
                .collect { current ->
                    updatePeriodicWork(current)
                    if (hasObservedTargets) {
                        if (current.debitFollowCount > previous.debitFollowCount) enqueueDebitSync()
                        if (current.forumFollowCount > previous.forumFollowCount) enqueueForumSync()
                    }
                    previous = current
                    hasObservedTargets = true
                }
        }
    }

    private fun observeConnectivity() {
        scope.launch {
            var wasOnline = connectivityObserver.isCurrentlyOnline()
            connectivityObserver.observe().distinctUntilChanged().collect { online ->
                if (online && !wasOnline) {
                    val state = notificationCenterRepository.observeState().first()
                    if (state.followedCanyons.isNotEmpty()) enqueueDebitSync()
                    if (state.hasForumTargets()) enqueueForumSync()
                }
                wasOnline = online
            }
        }
    }

    private fun updatePeriodicWork(targets: TrackedTargets) {
        val workManager = WorkManager.getInstance(context)
        if (targets.debitFollowCount > 0) {
            workManager.enqueueUniquePeriodicWork(
                DEBIT_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicDebitRequest(),
            )
        } else {
            workManager.cancelUniqueWork(DEBIT_PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(DEBIT_IMMEDIATE_WORK_NAME)
        }
        if (targets.forumFollowCount > 0) {
            workManager.enqueueUniquePeriodicWork(
                FORUM_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicForumRequest(),
            )
        } else {
            workManager.cancelUniqueWork(FORUM_PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(FORUM_IMMEDIATE_WORK_NAME)
        }
    }

    private fun enqueueDebitSync() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            DEBIT_IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DebitNotificationSyncWorker>()
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build(),
        )
    }

    private fun enqueueForumSync() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            FORUM_IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ForumNotificationSyncWorker>()
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build(),
        )
    }

    private fun periodicDebitRequest() = PeriodicWorkRequestBuilder<DebitNotificationSyncWorker>(PERIODIC_MINUTES, TimeUnit.MINUTES)
        .setConstraints(networkConstraints())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
        .addTag(DEBIT_PERIODIC_WORK_NAME)
        .build()

    private fun periodicForumRequest() = PeriodicWorkRequestBuilder<ForumNotificationSyncWorker>(PERIODIC_MINUTES, TimeUnit.MINUTES)
        .setConstraints(networkConstraints())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
        .addTag(FORUM_PERIODIC_WORK_NAME)
        .build()

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private data class TrackedTargets(val debitFollowCount: Int = 0, val forumFollowCount: Int = 0)

    private fun kotlinx.coroutines.flow.Flow<NotificationCenterState>.mapToTrackedTargets() = map { state ->
        TrackedTargets(
            debitFollowCount = state.followedCanyons.size,
            forumFollowCount = state.followedForumCategories.size + state.followedForumThreads.size,
        )
    }

    private fun NotificationCenterState.hasForumTargets(): Boolean =
        followedForumCategories.isNotEmpty() || followedForumThreads.isNotEmpty()

    private companion object {
        const val PERIODIC_MINUTES = 15L
        const val BACKOFF_MINUTES = 10L
        const val DEBIT_PERIODIC_WORK_NAME = "notification-debit-sync"
        const val FORUM_PERIODIC_WORK_NAME = "notification-forum-sync"
        const val DEBIT_IMMEDIATE_WORK_NAME = "notification-debit-sync-immediate"
        const val FORUM_IMMEDIATE_WORK_NAME = "notification-forum-sync-immediate"
    }
}
