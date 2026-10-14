package fr.descentecanyon.app.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Sends the debit reports stored offline as soon as possible: in the app process when the
 * connection comes back or after a login, and through WorkManager when the app is closed.
 */
@Singleton
class PendingDebitSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: DebitSubmissionRepository,
    private val pendingDebitSync: PendingDebitSync,
    private val connectivityObserver: ConnectivityObserver,
    private val authRepository: AuthRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(
                connectivityObserver.observe(),
                repository.observePendingCount().map { it > 0 },
                authRepository.authState.map { it is AuthState.Connected },
            ) { online, hasPending, loggedIn -> SyncTrigger(online, hasPending, loggedIn) }
                .distinctUntilChanged()
                .collect { trigger ->
                    if (!trigger.hasPending) return@collect
                    enqueueBackgroundSync()
                    if (trigger.online) {
                        suspendRunCatching { pendingDebitSync.run() }
                            .onFailure { Log.w(TAG, "Pending debit sync failed", it) }
                    }
                }
        }
    }

    private fun enqueueBackgroundSync() {
        val request = OneTimeWorkRequestBuilder<PendingDebitSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private data class SyncTrigger(val online: Boolean, val hasPending: Boolean, val loggedIn: Boolean)

    private companion object {
        const val TAG = "PendingDebitSync"
        const val WORK_NAME = "pending-debit-sync"
        const val BACKOFF_MINUTES = 2L
    }
}
