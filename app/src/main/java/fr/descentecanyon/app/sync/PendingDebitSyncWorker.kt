package fr.descentecanyon.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.domain.error.isTransient

/** Background delivery of the debit reports stored offline, run by WorkManager once online. */
class PendingDebitSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val sync = EntryPointAccessors.fromApplication(applicationContext, SyncWorkerEntryPoint::class.java)
            .pendingDebitSync()
        val stoppedBy = sync.run().stoppedBy ?: return Result.success()
        // A login is needed: PendingDebitSyncScheduler sends the queue again after the next login.
        return if (stoppedBy.isTransient()) Result.retry() else Result.success()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncWorkerEntryPoint {
    fun pendingDebitSync(): PendingDebitSync
}
