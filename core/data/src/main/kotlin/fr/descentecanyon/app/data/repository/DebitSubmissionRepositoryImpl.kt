package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.PendingDebitSubmissionDao
import fr.descentecanyon.app.data.local.user.entity.PendingDebitSubmissionEntity
import fr.descentecanyon.app.data.remote.scraper.DebitSubmissionRemoteSource
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.isTransient
import fr.descentecanyon.app.domain.error.requiresLogin
import fr.descentecanyon.app.domain.error.toAppError
import fr.descentecanyon.app.domain.model.AirTemperature
import fr.descentecanyon.app.domain.model.DebitDelivery
import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.DebitSubmissionStatus
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.model.PendingDebitRejection
import fr.descentecanyon.app.domain.model.PendingDebitSubmission
import fr.descentecanyon.app.domain.model.PendingDebitSyncResult
import fr.descentecanyon.app.domain.model.RejectedDebitSubmission
import fr.descentecanyon.app.domain.model.SentDebitSubmission
import fr.descentecanyon.app.domain.model.WaterTemperature
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class DebitSubmissionRepositoryImpl @Inject constructor(
    private val pendingDao: PendingDebitSubmissionDao,
    private val remoteSource: DebitSubmissionRemoteSource,
    private val connectivityObserver: ConnectivityObserver,
    private val clock: Clock,
) : DebitSubmissionRepository {

    // One sync at a time: the scheduler and the background worker may both trigger it.
    private val syncMutex = Mutex()
    private val lastSyncFailure = MutableStateFlow<AppError?>(null)

    override suspend fun submit(submission: DebitSubmission): Result<DebitSubmissionStatus> {
        if (!connectivityObserver.isCurrentlyOnline()) {
            queueSubmission(submission)
            return Result.success(DebitSubmissionStatus.QUEUED_OFFLINE)
        }

        return remoteSource.submit(submission).fold(
            onSuccess = { delivery -> Result.success(delivery.toStatus()) },
            onFailure = { throwable ->
                if (throwable.toAppError().isTransient()) {
                    // The report may have reached the site before the failure: it is read back before a new send.
                    queueSubmission(submission, failedAttempts = 1)
                    Result.success(DebitSubmissionStatus.QUEUED_OFFLINE)
                } else {
                    Result.failure(throwable)
                }
            },
        )
    }

    override suspend fun syncPending(): PendingDebitSyncResult = syncMutex.withLock {
        val sent = mutableListOf<SentDebitSubmission>()
        val rejected = mutableListOf<RejectedDebitSubmission>()
        for (pending in pendingDao.getSendable()) {
            val submission = pending.toDomain()
            // A failed attempt may still have reached the site (timeout, unreadable answer): never publish twice.
            if (pending.failedAttempts > 0 && remoteSource.isPublished(submission)) {
                pendingDao.deleteById(pending.id)
                sent += SentDebitSubmission(canyonId = submission.canyonId, observationDate = submission.observationDate, confirmed = true)
                continue
            }
            val result = remoteSource.submit(submission)
            val delivery = result.getOrNull()
            if (delivery != null) {
                pendingDao.deleteById(pending.id)
                sent += SentDebitSubmission(
                    canyonId = submission.canyonId,
                    observationDate = submission.observationDate,
                    confirmed = delivery == DebitDelivery.CONFIRMED,
                )
                continue
            }
            val error = result.exceptionOrNull()!!.toAppError()
            if (error.isTransient() || error.requiresLogin()) {
                if (error.isTransient()) pendingDao.recordFailedAttempt(pending.id)
                lastSyncFailure.value = error
                // Later reports would fail the same way: keep the whole queue for the next attempt.
                return@withLock PendingDebitSyncResult(sent = sent, rejected = rejected, stoppedBy = error)
            }
            // Kept with the reason: the user can correct the cause and send it again, or delete it.
            pendingDao.markRejected(pending.id, clock.millis(), (error as? AppError.Rejected)?.serverMessage)
            rejected += RejectedDebitSubmission(
                canyonId = submission.canyonId,
                observationDate = submission.observationDate,
                error = error,
            )
        }
        lastSyncFailure.value = null
        PendingDebitSyncResult(sent = sent, rejected = rejected)
    }

    override fun observePendingCount(): Flow<Int> = pendingDao.observeCount()

    override fun observePending(): Flow<List<PendingDebitSubmission>> = pendingDao.observeAll().map { entities ->
        entities.map { entity ->
            PendingDebitSubmission(
                id = entity.id,
                submission = entity.toDomain(),
                createdAtEpochMs = entity.createdAt,
                rejection = entity.rejectedAt?.let { PendingDebitRejection(atEpochMs = it, siteMessage = entity.rejectionReason) },
            )
        }
    }

    override fun observeLastSyncFailure(): Flow<AppError?> = lastSyncFailure.asStateFlow()

    override suspend fun retryPending(id: Long) {
        pendingDao.clearRejection(id)
    }

    override suspend fun deletePending(id: Long) {
        pendingDao.deleteById(id)
    }

    private suspend fun queueSubmission(submission: DebitSubmission, failedAttempts: Int = 0) {
        pendingDao.insert(submission.toEntity().copy(failedAttempts = failedAttempts))
    }
}

private fun DebitDelivery.toStatus(): DebitSubmissionStatus = when (this) {
    DebitDelivery.CONFIRMED -> DebitSubmissionStatus.SUBMITTED
    DebitDelivery.UNCONFIRMED -> DebitSubmissionStatus.SUBMITTED_UNCONFIRMED
}

private fun DebitSubmission.toEntity(): PendingDebitSubmissionEntity = PendingDebitSubmissionEntity(
    canyonId = canyonId,
    observerName = observerName,
    observerEmail = observerEmail,
    observationDate = observationDate.toString(),
    isDescended = observationType == ObservationType.PARCOURU,
    debitLevel = debitLevel.name,
    waterTemperature = waterTemperature.name,
    airTemperature = airTemperature.name,
    comment = comment,
    personalComment = personalComment,
)

private fun PendingDebitSubmissionEntity.toDomain(): DebitSubmission = DebitSubmission(
    canyonId = canyonId,
    observerName = observerName,
    observerEmail = observerEmail,
    observationDate = runCatching { LocalDate.parse(observationDate) }.getOrElse { LocalDate.now() },
    observationType = if (isDescended) ObservationType.PARCOURU else ObservationType.NON_PARCOURU,
    debitLevel = runCatching { NiveauDebit.valueOf(debitLevel) }.getOrDefault(NiveauDebit.INCONNU),
    waterTemperature = runCatching { WaterTemperature.valueOf(waterTemperature) }.getOrDefault(WaterTemperature.INCONNUE),
    airTemperature = runCatching { AirTemperature.valueOf(airTemperature) }.getOrDefault(AirTemperature.INCONNUE),
    comment = comment,
    personalComment = personalComment,
)
