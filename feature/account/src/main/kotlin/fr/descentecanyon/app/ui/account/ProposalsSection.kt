package fr.descentecanyon.app.ui.account

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonProposal
import fr.descentecanyon.app.domain.model.ProposalKind
import fr.descentecanyon.app.domain.model.ProposalRejectionReason
import fr.descentecanyon.app.domain.model.ProposalStatus
import fr.descentecanyon.app.ui.test.TestTags
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The canyons proposed from this phone and what became of them; a new one starts here. */
@Composable
internal fun ProposalsSection(
    canPropose: Boolean,
    proposals: List<CanyonProposal>,
    onPropose: () -> Unit,
    onOpenCanyon: (Int) -> Unit,
    onWithdraw: (CanyonProposal) -> Unit,
) {
    var withdrawing by remember { mutableStateOf<CanyonProposal?>(null) }
    withdrawing?.let { proposal ->
        AlertDialog(
            onDismissRequest = { withdrawing = null },
            text = { Text(stringResource(R.string.proposal_withdraw_confirm, proposal.name)) },
            confirmButton = {
                TextButton(onClick = {
                    withdrawing = null
                    onWithdraw(proposal)
                }) { Text(stringResource(R.string.proposal_withdraw), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { withdrawing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    AccountSection(stringResource(R.string.account_section_proposals), modifier = Modifier.testTag(TestTags.MY_ACCOUNT_PROPOSALS)) {
        if (canPropose) {
            AccountRow(
                icon = Icons.Default.AddLocationAlt,
                title = stringResource(R.string.account_propose_canyon),
                subtitle = stringResource(R.string.account_propose_canyon_hint),
                onClick = onPropose,
            )
        }
        val formatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
        proposals.take(MAX_SHOWN).forEach { proposal ->
            val date = (proposal.decidedAt ?: proposal.createdAt).atZone(ZoneId.systemDefault()).toLocalDate().format(formatter)
            val status = stringResource(proposal.status.labelRes())
            val reason = proposal.reason?.labelRes()?.let { stringResource(it) }
            val subtitle = listOfNotNull("$status · $date", reason, proposal.note?.let { "« $it »" }).joinToString("\n")
            val canyonId = proposal.canyonId
            AccountRow(
                icon = when (proposal.status) {
                    ProposalStatus.PENDING -> Icons.Default.Schedule
                    ProposalStatus.APPROVED -> Icons.Default.CheckCircle
                    ProposalStatus.REJECTED -> Icons.Default.Block
                    ProposalStatus.WITHDRAWN -> Icons.AutoMirrored.Filled.Undo
                },
                title = if (proposal.kind ==
                    ProposalKind.EDIT
                ) {
                    stringResource(R.string.proposal_kind_edit, proposal.name)
                } else {
                    proposal.name
                },
                subtitle = subtitle,
                onClick = if (proposal.status == ProposalStatus.APPROVED && canyonId != null) ({ onOpenCanyon(canyonId) }) else null,
                trailing = if (canPropose && proposal.status == ProposalStatus.PENDING) {
                    { TextButton(onClick = { withdrawing = proposal }) { Text(stringResource(R.string.proposal_withdraw)) } }
                } else {
                    null
                },
            )
        }
    }
}

private fun ProposalStatus.labelRes(): Int = when (this) {
    ProposalStatus.PENDING -> R.string.proposal_status_pending
    ProposalStatus.APPROVED -> R.string.proposal_status_approved
    ProposalStatus.REJECTED -> R.string.proposal_status_rejected
    ProposalStatus.WITHDRAWN -> R.string.proposal_status_withdrawn
}

/** Null when the operator's note says it all. */
private fun ProposalRejectionReason.labelRes(): Int? = when (this) {
    ProposalRejectionReason.DUPLICATE -> R.string.proposal_reason_duplicate
    ProposalRejectionReason.EXISTS_ON_SITE -> R.string.proposal_reason_exists_on_site
    ProposalRejectionReason.FORBIDDEN -> R.string.proposal_reason_forbidden
    ProposalRejectionReason.INCOMPLETE -> R.string.proposal_reason_incomplete
    ProposalRejectionReason.INCORRECT -> R.string.proposal_reason_incorrect
    ProposalRejectionReason.NOT_A_CANYON -> R.string.proposal_reason_not_a_canyon
    ProposalRejectionReason.OTHER, ProposalRejectionReason.BLOCKED -> null
}

private const val MAX_SHOWN = 10
