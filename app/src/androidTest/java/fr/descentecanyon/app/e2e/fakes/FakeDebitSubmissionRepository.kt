package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.DebitSubmissionStatus
import fr.descentecanyon.app.domain.model.PendingDebitSubmission
import fr.descentecanyon.app.domain.model.PendingDebitSyncResult
import fr.descentecanyon.app.domain.model.SentDebitSubmission
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
        val synced = E2eFixtureState.queuedSubmissions.value
        E2eFixtureState.queuedSubmissions.value = emptyList()
        return PendingDebitSyncResult(
            sent = synced.map { SentDebitSubmission(canyonId = it.canyonId, observationDate = it.observationDate, confirmed = true) },
        )
    }

    override fun observePendingCount(): Flow<Int> = E2eFixtureState.queuedSubmissions.map { it.size }

    override fun observePending(): Flow<List<PendingDebitSubmission>> = E2eFixtureState.queuedSubmissions.map { queued ->
        queued.mapIndexed { index, submission ->
            PendingDebitSubmission(id = index.toLong() + 1, submission = submission, createdAtEpochMs = 0L)
        }
    }

    override fun observeLastSyncFailure(): Flow<AppError?> = flowOf(null)

    override suspend fun retryPending(id: Long) = Unit

    override suspend fun deletePending(id: Long) {
        E2eFixtureState.queuedSubmissions.value =
            E2eFixtureState.queuedSubmissions.value.filterIndexed { index, _ -> index.toLong() + 1 != id }
    }
}
