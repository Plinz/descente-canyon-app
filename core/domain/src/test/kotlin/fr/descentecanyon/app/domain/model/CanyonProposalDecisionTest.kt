package fr.descentecanyon.app.domain.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class CanyonProposalDecisionTest {
    private fun proposal(id: String, status: ProposalStatus) = CanyonProposal(
        id = id,
        kind = ProposalKind.NEW,
        canyonId = if (status == ProposalStatus.APPROVED) 1_000_001 else null,
        name = "Canyon $id",
        status = status,
        createdAt = Instant.parse("2026-11-26T10:00:00Z"),
    )

    @Test
    fun `only the proposals that were waiting and got reviewed are told`() {
        val before = listOf(
            proposal("a", ProposalStatus.PENDING),
            proposal("b", ProposalStatus.PENDING),
            proposal("c", ProposalStatus.PENDING),
            proposal("d", ProposalStatus.APPROVED),
            proposal("e", ProposalStatus.PENDING),
        )
        val after = listOf(
            proposal("a", ProposalStatus.APPROVED),
            proposal("b", ProposalStatus.REJECTED),
            proposal("c", ProposalStatus.PENDING),
            // Already published at the previous check: told then.
            proposal("d", ProposalStatus.APPROVED),
            // Withdrawn from another screen: nothing to tell.
            proposal("e", ProposalStatus.WITHDRAWN),
            // Sent since (from this phone, it knows): not in the previous list.
            proposal("f", ProposalStatus.APPROVED),
        )

        assertEquals(listOf("a", "b"), decidedSince(before, after).map { it.id })
    }

    @Test
    fun `nothing waiting means nothing to tell`() {
        assertEquals(emptyList<CanyonProposal>(), decidedSince(emptyList(), listOf(proposal("a", ProposalStatus.APPROVED))))
    }
}
