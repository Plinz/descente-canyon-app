package fr.descentecanyon.app.sync

import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.AppException
import fr.descentecanyon.app.domain.model.CanyonProposal
import fr.descentecanyon.app.domain.model.ProposalKind
import fr.descentecanyon.app.domain.model.ProposalStatus
import fr.descentecanyon.app.domain.repository.CanyonProposalRepository
import fr.descentecanyon.app.notifications.AppNotificationPublisher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ProposalDecisionCheckTest {

    private val repository = mockk<CanyonProposalRepository>()
    private val publisher = mockk<AppNotificationPublisher>(relaxed = true)
    private val check = ProposalDecisionCheck(repository, publisher)

    private fun proposal(id: String, status: ProposalStatus) = CanyonProposal(
        id = id,
        kind = ProposalKind.NEW,
        canyonId = if (status == ProposalStatus.APPROVED) 1_000_004 else null,
        name = "Canyon $id",
        status = status,
        createdAt = Instant.parse("2026-11-26T10:00:00Z"),
    )

    @Test
    fun `the reviewed proposals are told once to the author`() = runTest {
        every { repository.observeMine() } returns
            flowOf(listOf(proposal("a", ProposalStatus.PENDING), proposal("b", ProposalStatus.PENDING)))
        val published = proposal("a", ProposalStatus.APPROVED)
        coEvery { repository.refreshMine() } returns Result.success(listOf(published, proposal("b", ProposalStatus.PENDING)))

        check.run()

        verify { publisher.publishDecidedProposals(listOf(published)) }
    }

    @Test
    fun `nothing waiting asks nothing of the server`() = runTest {
        every { repository.observeMine() } returns flowOf(listOf(proposal("a", ProposalStatus.APPROVED)))

        check.run()

        coVerify(exactly = 0) { repository.refreshMine() }
        verify(exactly = 0) { publisher.publishDecidedProposals(any()) }
    }

    @Test
    fun `a failed check tells nothing`() = runTest {
        every { repository.observeMine() } returns flowOf(listOf(proposal("a", ProposalStatus.PENDING)))
        coEvery { repository.refreshMine() } returns Result.failure(AppException(AppError.NetworkFailure))

        check.run()

        verify(exactly = 0) { publisher.publishDecidedProposals(any()) }
    }
}
