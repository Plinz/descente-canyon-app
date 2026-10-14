package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.DebitSubmissionStatus
import fr.descentecanyon.app.domain.model.PendingDebitSyncResult
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class FakeDebitSubmissionRepository @Inject constructor(private val connectivityObserver: FakeConnectivityObserver) :
    DebitSubmissionRepository {
    override suspend fun submit(submission: DebitSubmission): Result<DebitSubmissionStatus> =
        if (connectivityObserver.isCurrentlyOnline()) {
            Result.success(DebitSubmissionStatus.SUBMITTED)
        } else {
            E2eFixtureState.queuedSubmissions.value = E2eFixtureState.queuedSubmissions.value + submission
            Result.success(DebitSubmissionStatus.QUEUED_OFFLINE)
        }

    override suspend fun syncPending(): PendingDebitSyncResult {
        if (!connectivityObserver.isCurrentlyOnline()) return PendingDebitSyncResult(stoppedBy = AppError.Offline)
        val synced = E2eFixtureState.queuedSubmissions.value.size
        E2eFixtureState.queuedSubmissions.value = emptyList()
        return PendingDebitSyncResult(sentCount = synced)
    }

    override fun observePendingCount(): Flow<Int> = E2eFixtureState.queuedSubmissions.map { it.size }
}
