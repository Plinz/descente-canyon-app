package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.PendingDebitSubmissionDao
import fr.descentecanyon.app.data.local.user.entity.PendingDebitSubmissionEntity
import fr.descentecanyon.app.data.remote.scraper.DebitSubmissionRemoteSource
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appErrorOrNull
import fr.descentecanyon.app.domain.error.failure
import fr.descentecanyon.app.domain.model.AirTemperature
import fr.descentecanyon.app.domain.model.DebitDelivery
import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.DebitSubmissionStatus
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.model.PendingDebitSyncResult
import fr.descentecanyon.app.domain.model.SentDebitSubmission
import fr.descentecanyon.app.domain.model.WaterTemperature
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebitSubmissionRepositoryImplTest {

    private val pendingDao = mockk<PendingDebitSubmissionDao>(relaxed = true)
    private val remoteSource = mockk<DebitSubmissionRemoteSource>()
    private val clock = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `offline submit queues observation`() = runTest {
        val connectivityObserver = object : ConnectivityObserver {
            override fun observe(): Flow<Boolean> = throw UnsupportedOperationException()
            override fun isCurrentlyOnline(): Boolean = false
        }
        val repository = DebitSubmissionRepositoryImpl(pendingDao, remoteSource, connectivityObserver, clock)

        val result = repository.submit(sampleSubmission()).getOrThrow()

        assertEquals(DebitSubmissionStatus.QUEUED_OFFLINE, result)
    }

    @Test
    fun `sync pending sends queued observations and clears them`() = runTest {
        val connectivityObserver = object : ConnectivityObserver {
            override fun observe(): Flow<Boolean> = throw UnsupportedOperationException()
            override fun isCurrentlyOnline(): Boolean = true
        }
        coEvery { pendingDao.getSendable() } returns listOf(
            PendingDebitSubmissionEntity(
                id = 9,
                canyonId = 2186,
                observerName = "Antoine",
                observerEmail = "antoine@example.com",
                observationDate = "2026-03-22",
                isDescended = true,
                debitLevel = NiveauDebit.CORRECT.name,
                waterTemperature = WaterTemperature.FROIDE.name,
                airTemperature = AirTemperature.BON.name,
                comment = "RAS",
            ),
        )
        coEvery { remoteSource.submit(match { it.canyonId == 2186 }) } returns Result.success(DebitDelivery.CONFIRMED)
        val repository = DebitSubmissionRepositoryImpl(pendingDao, remoteSource, connectivityObserver, clock)

        val result = repository.syncPending()

        assertEquals(
            PendingDebitSyncResult(
                sent = listOf(SentDebitSubmission(canyonId = 2186, observationDate = LocalDate.of(2026, 3, 22), confirmed = true)),
            ),
            result,
        )
        coVerify { remoteSource.submit(any()) }
        coVerify { pendingDao.deleteById(9) }
    }

    @Test
    fun `sync pending keeps reports refused by the site for the user and continues`() = runTest {
        coEvery { pendingDao.getSendable() } returns listOf(
            pendingEntity(id = 1, canyonId = 100),
            pendingEntity(id = 2, canyonId = 200),
            pendingEntity(id = 3, canyonId = 300),
        )
        coEvery { remoteSource.submit(match { it.canyonId == 100 }) } returns Result.success(DebitDelivery.CONFIRMED)
        coEvery { remoteSource.submit(match { it.canyonId == 200 }) } returns failure(AppError.Rejected("Date invalide"))
        coEvery { remoteSource.submit(match { it.canyonId == 300 }) } returns Result.success(DebitDelivery.UNCONFIRMED)
        val repository = DebitSubmissionRepositoryImpl(pendingDao, remoteSource, onlineObserver(), clock)

        val result = repository.syncPending()

        assertEquals(2, result.sentCount)
        assertEquals(listOf(100 to true, 300 to false), result.sent.map { it.canyonId to it.confirmed })
        assertEquals(listOf(200), result.rejected.map { it.canyonId })
        assertEquals(AppError.Rejected("Date invalide"), result.rejected.single().error)
        assertNull(result.stoppedBy)
        coVerify { pendingDao.deleteById(1) }
        coVerify(exactly = 0) { pendingDao.deleteById(2) }
        coVerify { pendingDao.markRejected(2, clock.millis(), "Date invalide") }
        coVerify { pendingDao.deleteById(3) }
    }

    @Test
    fun `a report whose sending failed is read back and never sent twice`() = runTest {
        coEvery { pendingDao.getSendable() } returns listOf(pendingEntity(id = 1, canyonId = 100).copy(failedAttempts = 1))
        coEvery { remoteSource.isPublished(any()) } returns true
        val repository = DebitSubmissionRepositoryImpl(pendingDao, remoteSource, onlineObserver(), clock)

        val result = repository.syncPending()

        assertEquals(listOf(100 to true), result.sent.map { it.canyonId to it.confirmed })
        coVerify(exactly = 0) { remoteSource.submit(any()) }
        coVerify { pendingDao.deleteById(1) }
    }

    @Test
    fun `a report never sent is not read back first`() = runTest {
        coEvery { pendingDao.getSendable() } returns listOf(pendingEntity(id = 1, canyonId = 100))
        coEvery { remoteSource.submit(any()) } returns Result.success(DebitDelivery.CONFIRMED)
        val repository = DebitSubmissionRepositoryImpl(pendingDao, remoteSource, onlineObserver(), clock)

        repository.syncPending()

        coVerify(exactly = 0) { remoteSource.isPublished(any()) }
    }

    @Test
    fun `a transient failure is counted and reported as the last sync failure`() = runTest {
        coEvery { pendingDao.getSendable() } returns listOf(pendingEntity(id = 1, canyonId = 100))
        coEvery { remoteSource.submit(any()) } returns failure(AppError.UnexpectedResponse)
        val repository = DebitSubmissionRepositoryImpl(pendingDao, remoteSource, onlineObserver(), clock)

        repository.syncPending()

        coVerify { pendingDao.recordFailedAttempt(1) }
        assertEquals(AppError.UnexpectedResponse, repository.observeLastSyncFailure().first())
    }

    @Test
    fun `an online submit that fails transiently is queued to be read back before a new send`() = runTest {
        coEvery { remoteSource.submit(any()) } returns failure(AppError.Timeout)
        val repository = DebitSubmissionRepositoryImpl(pendingDao, remoteSource, onlineObserver(), clock)

        assertEquals(DebitSubmissionStatus.QUEUED_OFFLINE, repository.submit(sampleSubmission()).getOrThrow())

        coVerify { pendingDao.insert(match { it.failedAttempts == 1 }) }
    }

    @Test
    fun `sync pending keeps the queue when the site cannot be reached`() = runTest {
        coEvery { pendingDao.getSendable() } returns listOf(
            pendingEntity(id = 1, canyonId = 100),
            pendingEntity(id = 2, canyonId = 200),
        )
        coEvery { remoteSource.submit(match { it.canyonId == 100 }) } returns failure(AppError.Timeout)
        val repository = DebitSubmissionRepositoryImpl(pendingDao, remoteSource, onlineObserver(), clock)

        val result = repository.syncPending()

        assertEquals(PendingDebitSyncResult(stoppedBy = AppError.Timeout), result)
        coVerify(exactly = 1) { remoteSource.submit(any()) }
        coVerify(exactly = 0) { pendingDao.deleteById(any()) }
    }

    @Test
    fun `sync pending waits for a login when the session expired`() = runTest {
        coEvery { pendingDao.getSendable() } returns listOf(pendingEntity(id = 1, canyonId = 100))
        coEvery { remoteSource.submit(any()) } returns failure(AppError.SessionExpired)
        val repository = DebitSubmissionRepositoryImpl(pendingDao, remoteSource, onlineObserver(), clock)

        assertEquals(AppError.SessionExpired, repository.syncPending().stoppedBy)
        coVerify(exactly = 0) { pendingDao.deleteById(any()) }
    }

    @Test
    fun `online submit reports an unconfirmed delivery`() = runTest {
        coEvery { remoteSource.submit(any()) } returns Result.success(DebitDelivery.UNCONFIRMED)
        val repository = DebitSubmissionRepositoryImpl(pendingDao, remoteSource, onlineObserver(), clock)

        assertEquals(DebitSubmissionStatus.SUBMITTED_UNCONFIRMED, repository.submit(sampleSubmission()).getOrThrow())
    }

    @Test
    fun `online submit returns site refusals instead of queueing them`() = runTest {
        coEvery { remoteSource.submit(any()) } returns failure(AppError.Rejected(null))
        val repository = DebitSubmissionRepositoryImpl(pendingDao, remoteSource, onlineObserver(), clock)

        assertEquals(AppError.Rejected(null), repository.submit(sampleSubmission()).appErrorOrNull())
        coVerify(exactly = 0) { pendingDao.insert(any()) }
    }

    @Test
    fun `pending queue exposes stored reports and deletes them`() = runTest {
        every { pendingDao.observeAll() } returns flowOf(listOf(pendingEntity(id = 4, canyonId = 2186)))
        val repository = DebitSubmissionRepositoryImpl(pendingDao, remoteSource, onlineObserver(), clock)

        val pending = repository.observePending().first().single()
        repository.deletePending(4)

        assertEquals(4L, pending.id)
        assertEquals(2186, pending.submission.canyonId)
        coVerify { pendingDao.deleteById(4) }
    }

    private fun onlineObserver() = object : ConnectivityObserver {
        override fun observe(): Flow<Boolean> = throw UnsupportedOperationException()
        override fun isCurrentlyOnline(): Boolean = true
    }

    @Test
    fun `online submit falls back to queue on IOException`() = runTest {
        val connectivityObserver = object : ConnectivityObserver {
            override fun observe(): Flow<Boolean> = throw UnsupportedOperationException()
            override fun isCurrentlyOnline(): Boolean = true
        }
        coEvery { remoteSource.submit(any()) } returns Result.failure(java.io.IOException("timeout"))
        val repository = DebitSubmissionRepositoryImpl(pendingDao, remoteSource, connectivityObserver, clock)

        val result = repository.submit(sampleSubmission()).getOrThrow()

        assertEquals(DebitSubmissionStatus.QUEUED_OFFLINE, result)
    }

    private fun pendingEntity(id: Long, canyonId: Int) = PendingDebitSubmissionEntity(
        id = id,
        canyonId = canyonId,
        observerName = "Test",
        observerEmail = "test@example.com",
        observationDate = "2026-03-22",
        isDescended = true,
        debitLevel = NiveauDebit.CORRECT.name,
        waterTemperature = WaterTemperature.FROIDE.name,
        airTemperature = AirTemperature.BON.name,
        comment = "Test",
    )

    private fun sampleSubmission() = DebitSubmission(
        canyonId = 2186,
        observerName = "Antoine",
        observerEmail = "antoine@example.com",
        observationDate = LocalDate.of(2026, 3, 22),
        observationType = ObservationType.PARCOURU,
        debitLevel = NiveauDebit.CORRECT,
        waterTemperature = WaterTemperature.FROIDE,
        airTemperature = AirTemperature.BON,
        comment = "Super canyon",
    )
}
