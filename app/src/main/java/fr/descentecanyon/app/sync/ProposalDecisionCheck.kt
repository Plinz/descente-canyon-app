package fr.descentecanyon.app.sync

import fr.descentecanyon.app.domain.model.ProposalStatus
import fr.descentecanyon.app.domain.model.decidedSince
import fr.descentecanyon.app.domain.repository.CanyonProposalRepository
import fr.descentecanyon.app.notifications.AppNotificationPublisher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Tells the author when a canyon proposed from this phone was reviewed: checked with the server's
 * manifest (twice a day), only while a proposal waits. A decision already seen in the user space is
 * not told again.
 */
@Singleton
class ProposalDecisionCheck @Inject constructor(
    private val proposals: CanyonProposalRepository,
    private val publisher: AppNotificationPublisher,
) {
    suspend fun run() {
        val before = proposals.observeMine().first()
        if (before.none { it.status == ProposalStatus.PENDING }) return
        val after = proposals.refreshMine().getOrNull() ?: return
        publisher.publishDecidedProposals(decidedSince(before, after))
    }
}
