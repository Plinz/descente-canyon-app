package fr.descentecanyon.app.ui.common

import androidx.annotation.StringRes
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.ProposalRejectionReason
import fr.descentecanyon.app.domain.model.ProposalStatus

/** What became of a proposed canyon, as the user space and the notifications say it. */
@StringRes
fun ProposalStatus.labelRes(): Int = when (this) {
    ProposalStatus.PENDING -> R.string.proposal_status_pending
    ProposalStatus.APPROVED -> R.string.proposal_status_approved
    ProposalStatus.REJECTED -> R.string.proposal_status_rejected
    ProposalStatus.WITHDRAWN -> R.string.proposal_status_withdrawn
}

/** Why the operator declined a proposal; null when the operator's note says it all. */
@StringRes
fun ProposalRejectionReason.labelRes(): Int? = when (this) {
    ProposalRejectionReason.DUPLICATE -> R.string.proposal_reason_duplicate
    ProposalRejectionReason.EXISTS_ON_SITE -> R.string.proposal_reason_exists_on_site
    ProposalRejectionReason.FORBIDDEN -> R.string.proposal_reason_forbidden
    ProposalRejectionReason.INCOMPLETE -> R.string.proposal_reason_incomplete
    ProposalRejectionReason.INCORRECT -> R.string.proposal_reason_incorrect
    ProposalRejectionReason.NOT_A_CANYON -> R.string.proposal_reason_not_a_canyon
    ProposalRejectionReason.OTHER, ProposalRejectionReason.BLOCKED -> null
}
