package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.DebitSubmissionStatus
import fr.descentecanyon.app.domain.model.PendingDebitSubmission
import fr.descentecanyon.app.domain.model.PendingDebitSyncResult
import kotlinx.coroutines.flow.Flow

interface DebitSubmissionRepository {
    /**
     * Sends the report, or stores it when the device is offline or the site cannot be reached.
     * Fails with a typed error when the site refuses it or a login is needed.
     */
    suspend fun submit(submission: DebitSubmission): Result<DebitSubmissionStatus>

    /** Sends the stored reports, oldest first; stops at the first transient or login failure. */
    suspend fun syncPending(): PendingDebitSyncResult

    /** Reports waiting to be sent (the ones refused by the site excluded). */
    fun observePendingCount(): Flow<Int>

    /** Reports waiting to be sent and reports refused by the site, oldest first. */
    fun observePending(): Flow<List<PendingDebitSubmission>>

    /** Why the last sending of the queue stopped (site unreachable, login...); null when it went through. */
    fun observeLastSyncFailure(): Flow<AppError?>

    /** Puts a report refused by the site back in the queue (the refusal may have been temporary). */
    suspend fun retryPending(id: Long)

    /** Removes a report from the queue without sending it. */
    suspend fun deletePending(id: Long)
}
