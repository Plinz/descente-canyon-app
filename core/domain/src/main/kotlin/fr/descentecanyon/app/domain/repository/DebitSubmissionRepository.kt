package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.DebitSubmissionStatus
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

    fun observePendingCount(): Flow<Int>
}
