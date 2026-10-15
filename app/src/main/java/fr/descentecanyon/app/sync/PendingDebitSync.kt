package fr.descentecanyon.app.sync

import fr.descentecanyon.app.domain.error.requiresLogin
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.PendingDebitSyncResult
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.notifications.AppNotificationPublisher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Sends the debit reports stored offline and tells the user what happened: reports sent, reports
 * refused by the site, or reports waiting for a login.
 */
@Singleton
class PendingDebitSync @Inject constructor(
    private val repository: DebitSubmissionRepository,
    private val canyonRepository: CanyonRepository,
    private val notificationPublisher: AppNotificationPublisher,
    private val loginReminder: PendingDebitLoginReminder,
) {
    suspend fun run(): PendingDebitSyncResult {
        val result = repository.syncPending()
        val canyonNames = canyonNames(result)
        if (result.sent.isNotEmpty()) {
            notificationPublisher.publishSentDebitSubmissions(result.sent, canyonNames)
        }
        if (result.rejected.isNotEmpty()) {
            notificationPublisher.publishRejectedDebitSubmissions(result.rejected, canyonNames)
        }
        val stoppedBy = result.stoppedBy
        when {
            stoppedBy == null -> {
                loginReminder.reset()
                notificationPublisher.cancelPendingDebitsLoginReminder()
            }
            stoppedBy.requiresLogin() && loginReminder.isDue() -> {
                val pendingCount = repository.observePendingCount().first()
                if (pendingCount > 0 && notificationPublisher.publishPendingDebitsLoginReminder(pendingCount)) {
                    loginReminder.markShown()
                }
            }
        }
        return result
    }

    private suspend fun canyonNames(result: PendingDebitSyncResult): Map<Int, String> {
        val ids = (result.sent.map { it.canyonId } + result.rejected.map { it.canyonId }).toSet()
        if (ids.isEmpty()) return emptyMap()
        return suspendRunCatching { canyonRepository.getCanyonSummaries(ids).associate { it.id to it.nom } }
            .getOrDefault(emptyMap())
    }
}
