package fr.descentecanyon.app.sync

import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.PendingDebitSyncResult
import fr.descentecanyon.app.domain.model.RejectedDebitSubmission
import fr.descentecanyon.app.domain.model.SentDebitSubmission
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.notifications.AppNotificationPublisher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PendingDebitSyncTest {

    private val repository = mockk<DebitSubmissionRepository>()
    private val canyonRepository = mockk<CanyonRepository> {
        coEvery { getCanyonSummaries(any()) } returns listOf(
            CanyonSummary(id = 2186, nom = "Riolan", pays = "France", cotation = "v3a2II", url = "https://example.test/2186"),
        )
    }
    private val publisher = mockk<AppNotificationPublisher>(relaxed = true)
    private val loginReminder = mockk<PendingDebitLoginReminder>(relaxed = true)
    private val sync = PendingDebitSync(repository, canyonRepository, publisher, loginReminder)

    private val date = LocalDate.of(2026, 10, 14)

    @Test
    fun `sent reports are confirmed with the canyon name`() = runTest {
        val sent = listOf(SentDebitSubmission(canyonId = 2186, observationDate = date, confirmed = true))
        coEvery { repository.syncPending() } returns PendingDebitSyncResult(sent = sent)

        sync.run()

        verify { publisher.publishSentDebitSubmissions(sent, mapOf(2186 to "Riolan")) }
        verify { loginReminder.reset() }
        verify { publisher.cancelPendingDebitsLoginReminder() }
    }

    @Test
    fun `refused reports are reported`() = runTest {
        val rejected = listOf(RejectedDebitSubmission(canyonId = 2186, observationDate = date, error = AppError.Rejected("Date invalide")))
        coEvery { repository.syncPending() } returns PendingDebitSyncResult(rejected = rejected)

        sync.run()

        verify { publisher.publishRejectedDebitSubmissions(rejected, mapOf(2186 to "Riolan")) }
        verify(exactly = 0) { publisher.publishSentDebitSubmissions(any(), any()) }
    }

    @Test
    fun `a queue waiting for a login reminds the user once`() = runTest {
        coEvery { repository.syncPending() } returns PendingDebitSyncResult(stoppedBy = AppError.SessionExpired)
        every { repository.observePendingCount() } returns flowOf(2)
        every { loginReminder.isDue(any()) } returns true
        every { publisher.publishPendingDebitsLoginReminder(2) } returns true

        sync.run()

        verify { publisher.publishPendingDebitsLoginReminder(2) }
        verify { loginReminder.markShown(any()) }
    }

    @Test
    fun `no reminder before the interval elapsed`() = runTest {
        coEvery { repository.syncPending() } returns PendingDebitSyncResult(stoppedBy = AppError.SessionExpired)
        every { loginReminder.isDue(any()) } returns false

        sync.run()

        verify(exactly = 0) { publisher.publishPendingDebitsLoginReminder(any()) }
    }

    @Test
    fun `connectivity failures neither remind nor reset`() = runTest {
        coEvery { repository.syncPending() } returns PendingDebitSyncResult(stoppedBy = AppError.Offline)

        sync.run()

        verify(exactly = 0) { publisher.publishPendingDebitsLoginReminder(any()) }
        verify(exactly = 0) { loginReminder.reset() }
    }
}
