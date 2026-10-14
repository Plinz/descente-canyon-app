package fr.descentecanyon.app.sync

import fr.descentecanyon.app.domain.model.PendingDebitSyncResult
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.notifications.AppNotificationPublisher
import javax.inject.Inject
import javax.inject.Singleton

/** Sends the debit reports stored offline and tells the user about the ones the site refused. */
@Singleton
class PendingDebitSync @Inject constructor(
    private val repository: DebitSubmissionRepository,
    private val notificationPublisher: AppNotificationPublisher,
) {
    suspend fun run(): PendingDebitSyncResult {
        val result = repository.syncPending()
        if (result.rejected.isNotEmpty()) {
            notificationPublisher.publishRejectedDebitSubmissions(result.rejected)
        }
        return result
    }
}
