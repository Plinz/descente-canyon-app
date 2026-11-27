package fr.descentecanyon.app.ui.account

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonProposal
import fr.descentecanyon.app.domain.model.ProposalKind
import fr.descentecanyon.app.domain.model.ProposalStatus
import fr.descentecanyon.app.ui.common.labelRes
import fr.descentecanyon.app.ui.test.TestTags
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What the user gives the community: the flow reports still to send, the canyons proposed from this
 * phone and what became of them; a new one starts here.
 */
@Composable
internal fun ProposalsSection(
    canPropose: Boolean,
    proposals: List<CanyonProposal>,
    onPropose: () -> Unit,
    onOpenCanyon: (Int) -> Unit,
    onWithdraw: (CanyonProposal) -> Unit,
    pendingDebitCount: Int = 0,
    onOpenPendingDebits: () -> Unit = {},
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
    AccountSection(stringResource(R.string.account_section_contributions), modifier = Modifier.testTag(TestTags.MY_ACCOUNT_PROPOSALS)) {
        if (pendingDebitCount > 0) {
            AccountRow(
                icon = Icons.Default.CloudUpload,
                title = stringResource(R.string.pending_debits_title),
                subtitle = pluralStringResource(R.plurals.debit_pending_count, pendingDebitCount, pendingDebitCount),
                onClick = onOpenPendingDebits,
                modifier = Modifier.testTag(TestTags.MY_ACCOUNT_PENDING_DEBITS),
            )
        }
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

private const val MAX_SHOWN = 10
