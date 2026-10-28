package fr.descentecanyon.app.ui.canyon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.ui.components.CanyonMemoDialog
import fr.descentecanyon.app.ui.components.DescendedBadge
import fr.descentecanyon.app.ui.components.LogbookEntryDialog
import fr.descentecanyon.app.ui.components.LogbookEntryLines
import fr.descentecanyon.app.ui.components.rememberLogbookDateFormatter
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.test.TestTags
import java.time.LocalDate

/**
 * "My canyon": the memo (what stays true from one outing to the next) and the outings of the
 * logbook in this canyon, in one card. Nothing in it is sent to the site.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MyCanyonCard(
    canyonId: Int,
    canyonName: String,
    memo: String?,
    entries: List<LogbookEntry>,
    knownCompanions: List<String>,
    onSaveMemo: (String) -> Unit,
    onSaveEntry: (LogbookEntry) -> Unit,
    onDeleteEntry: (Long) -> Unit,
    onReportFlow: (LogbookEntry) -> Unit,
    onOpenLogbook: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDcColors.current
    val formatter = rememberLogbookDateFormatter()
    var editingMemo by rememberSaveable { mutableStateOf(false) }
    var memoExpanded by rememberSaveable { mutableStateOf(false) }
    var addingEntry by rememberSaveable { mutableStateOf(false) }
    var editedEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var reportPromptDate by rememberSaveable { mutableStateOf<String?>(null) }
    var lastAdded by rememberSaveable { mutableStateOf<String?>(null) }

    DcCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(TestTags.DETAIL_LOGBOOK),
        contentPadding = 14.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Private, told by a lock here and in full where the memo is written.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = colors.textSecondary)
                Text(
                    stringResource(R.string.my_canyon_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (entries.isNotEmpty()) {
                    DescendedBadge(text = pluralStringResource(R.plurals.my_canyon_times, entries.size, entries.size))
                }
                Icon(
                    Icons.Default.Lock,
                    contentDescription = stringResource(R.string.personal_note_private),
                    tint = colors.textMuted,
                    modifier = Modifier.size(16.dp),
                )
            }

            memo?.let { text ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { memoExpanded = !memoExpanded }.testTag(TestTags.DETAIL_MEMO),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (memoExpanded) Int.MAX_VALUE else MEMO_COLLAPSED_LINES,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    MemoEditButton(onClick = { editingMemo = true })
                }
            }

            // The last descent; the others are in the logbook.
            entries.firstOrNull()?.let { last ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { editedEntryId = last.id },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LogbookEntryLines(entry = last, title = last.date.format(formatter), modifier = Modifier.weight(1f))
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.logbook_edit_title),
                        tint = colors.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.Center,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (memo == null) {
                    CardAction(
                        icon = Icons.AutoMirrored.Filled.NoteAdd,
                        label = stringResource(R.string.my_canyon_memo),
                        onClick = { editingMemo = true },
                        modifier = Modifier.testTag(TestTags.DETAIL_MEMO),
                    )
                }
                CardAction(
                    icon = if (entries.isEmpty()) Icons.Default.CheckCircle else Icons.Default.Add,
                    label = stringResource(if (entries.isEmpty()) R.string.my_canyon_descended else R.string.my_canyon_add_descent),
                    onClick = { addingEntry = true },
                    modifier = Modifier.testTag(TestTags.DETAIL_LOGBOOK_ADD),
                )
                if (entries.size > 1) {
                    TextButton(onClick = onOpenLogbook) {
                        Text(pluralStringResource(R.plurals.my_canyon_all_outings, entries.size, entries.size))
                    }
                }
            }
        }
    }

    if (editingMemo) {
        CanyonMemoDialog(
            canyonName = null,
            initialText = memo.orEmpty(),
            onDismiss = { editingMemo = false },
            onSave = { text ->
                onSaveMemo(text)
                editingMemo = false
            },
        )
    }
    if (addingEntry) {
        LogbookEntryDialog(
            canyonId = canyonId,
            canyonName = canyonName,
            initial = null,
            knownCompanions = knownCompanions,
            onDismiss = { addingEntry = false },
            onSave = { entry ->
                onSaveEntry(entry)
                addingEntry = false
                lastAdded = entry.debitLevel?.name
                // A flow seen weeks ago no longer helps anyone decide: only recent outings are worth a report.
                if (!entry.date.isBefore(LocalDate.now().minusDays(REPORT_PROMPT_DAYS))) reportPromptDate = entry.date.toString()
            },
        )
    }
    editedEntryId?.let { id ->
        val entry = entries.firstOrNull { it.id == id }
        if (entry == null) {
            editedEntryId = null
        } else {
            LogbookEntryDialog(
                canyonId = canyonId,
                canyonName = canyonName,
                initial = entry,
                knownCompanions = knownCompanions,
                onDismiss = { editedEntryId = null },
                onSave = { edited ->
                    onSaveEntry(edited)
                    editedEntryId = null
                },
                onDelete = {
                    onDeleteEntry(entry.id)
                    editedEntryId = null
                },
            )
        }
    }
    reportPromptDate?.let { date ->
        AlertDialog(
            onDismissRequest = { reportPromptDate = null },
            text = { Text(stringResource(R.string.logbook_added_report_prompt)) },
            confirmButton = {
                TextButton(onClick = {
                    reportPromptDate = null
                    onReportFlow(
                        LogbookEntry(
                            canyonId = canyonId,
                            canyonName = canyonName,
                            date = LocalDate.parse(date),
                            debitLevel = lastAdded?.let { name -> NiveauDebit.entries.firstOrNull { it.name == name } },
                        ),
                    )
                }) { Text(stringResource(R.string.outing_report_action)) }
            },
            dismissButton = { TextButton(onClick = { reportPromptDate = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** One of the actions of the card, all alike. */
@Composable
private fun CardAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding, modifier = modifier) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Text(label, modifier = Modifier.padding(start = ButtonDefaults.IconSpacing), maxLines = 1)
    }
}

@Composable
private fun MemoEditButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            Icons.Default.Edit,
            contentDescription = stringResource(R.string.personal_note_edit),
            tint = LocalDcColors.current.textMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

private const val MEMO_COLLAPSED_LINES = 4
private const val REPORT_PROMPT_DAYS = 30L
