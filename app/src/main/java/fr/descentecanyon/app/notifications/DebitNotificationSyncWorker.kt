package fr.descentecanyon.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import fr.descentecanyon.app.domain.model.TrackedActivityType
import kotlinx.coroutines.flow.first

class DebitNotificationSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            NotificationWorkerEntryPoint::class.java,
        )
        val repository = entryPoint.notificationCenterRepository()
        if (repository.observeState().first().followedCanyons.isEmpty()) return Result.success()

        val debits = entryPoint.debitRepository().refreshLatestDebits(MAX_DEBITS_TO_SCAN)
            .getOrElse { return Result.retry() }
            .items
        repository.syncFetchedDebits(debits)
        val pendingEvents = repository.pendingEvents(TrackedActivityType.DEBIT)
        if (entryPoint.appNotificationPublisher().publishDebitEvents(pendingEvents)) {
            repository.markEventsDelivered(pendingEvents.map { it.id })
        }
        return Result.success()
    }

    private companion object {
        const val MAX_DEBITS_TO_SCAN = 120
    }
}
