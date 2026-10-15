package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.PendingDebitSubmissionDao
import fr.descentecanyon.app.data.local.user.entity.PendingDebitSubmissionEntity
import fr.descentecanyon.app.data.remote.scraper.DebitSubmissionRemoteSource
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.error.isTransient
import fr.descentecanyon.app.domain.error.requiresLogin
import fr.descentecanyon.app.domain.error.toAppError
import fr.descentecanyon.app.domain.model.AirTemperature
import fr.descentecanyon.app.domain.model.DebitDelivery
import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.DebitSubmissionStatus
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.model.PendingDebitSubmission
import fr.descentecanyon.app.domain.model.PendingDebitSyncResult
import fr.descentecanyon.app.domain.model.RejectedDebitSubmission
import fr.descentecanyon.app.domain.model.SentDebitSubmission
import fr.descentecanyon.app.domain.model.WaterTemperature
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class DebitSubmissionRepositoryImpl @Inject constructor(
    private val pendingDao: PendingDebitSubmissionDao,
    private val remoteSource: DebitSubmissionRemoteSource,
    private val connectivityObserver: ConnectivityObserver,
) : DebitSubmissionRepository {

    // One sync at a time: the scheduler and the background worker may both trigger it.
    private val syncMutex = Mutex()

    override suspend fun submit(submission: DebitSubmission): Result<DebitSubmissionStatus> {
        if (!connectivityObserver.isCurrentlyOnline()) {
            queueSubmission(submission)
            return Result.success(DebitSubmissionStatus.QUEUED_OFFLINE)
        }

        return remoteSource.submit(submission).fold(
            onSuccess = { delivery -> Result.success(delivery.toStatus()) },
            onFailure = { throwable ->
                if (throwable.toAppError().isTransient()) {
                    queueSubmission(submission)
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
        for (pending in pendingDao.getAll()) {
            val submission = pending.toDomain()
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
                // Later reports would fail the same way: keep the whole queue for the next attempt.
                return@withLock PendingDebitSyncResult(sent = sent, rejected = rejected, stoppedBy = error)
            }
            pendingDao.deleteById(pending.id)
            rejected += RejectedDebitSubmission(
                canyonId = submission.canyonId,
                observationDate = submission.observationDate,
                error = error,
            )
        }
        PendingDebitSyncResult(sent = sent, rejected = rejected)
    }

    override fun observePendingCount(): Flow<Int> = pendingDao.observeCount()

    override fun observePending(): Flow<List<PendingDebitSubmission>> = pendingDao.observeAll().map { entities ->
        entities.map { entity ->
            PendingDebitSubmission(id = entity.id, submission = entity.toDomain(), createdAtEpochMs = entity.createdAt)
        }
    }

    override suspend fun deletePending(id: Long) {
        pendingDao.deleteById(id)
    }

    private suspend fun queueSubmission(submission: DebitSubmission) {
        pendingDao.insert(submission.toEntity())
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
