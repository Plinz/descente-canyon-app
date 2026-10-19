package fr.descentecanyon.app.ui.canyon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.DurationRange
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.ui.components.CanyonMemoDialog
import fr.descentecanyon.app.ui.components.LogbookEntryDialog
import fr.descentecanyon.app.ui.components.LogbookEntryLines
import fr.descentecanyon.app.ui.components.formatDurationMinutes
import fr.descentecanyon.app.ui.components.formatDurationRange
import fr.descentecanyon.app.ui.components.rememberLogbookDateFormatter
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.test.TestTags
import java.time.LocalDate

/**
 * "My canyon": the memo (what stays true from one outing to the next) and the outings of the
 * logbook in this canyon, in one card. Nothing in it is sent to the site.
 */
@Composable
internal fun MyCanyonCard(
    canyonId: Int,
    canyonName: String,
    memo: String?,
    entries: List<LogbookEntry>,
    topoDuration: DurationRange?,
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = colors.textSecondary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.my_canyon_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.personal_note_private),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }

            if (memo == null) {
                TextButton(
                    onClick = { editingMemo = true },
                    contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
                    modifier = Modifier.testTag(TestTags.DETAIL_MEMO),
                ) {
                    Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(stringResource(R.string.personal_note_add), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { memoExpanded = !memoExpanded }.testTag(TestTags.DETAIL_MEMO),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.personal_note_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.textSecondary,
                        )
                        Text(
                            memo,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (memoExpanded) Int.MAX_VALUE else MEMO_COLLAPSED_LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    MemoEditButton(onClick = { editingMemo = true })
                }
            }

            HorizontalDivider(color = colors.borderSubtle)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = if (entries.isEmpty()) {
                        stringResource(R.string.logbook_card_empty)
                    } else {
                        pluralStringResource(R.plurals.logbook_card_count, entries.size, entries.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { addingEntry = true },
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    modifier = Modifier.testTag(TestTags.DETAIL_LOGBOOK_ADD),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(stringResource(R.string.logbook_save), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }

            durationComparison(entries, topoDuration)?.let { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }

            entries.take(SHOWN_ENTRIES).forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { editedEntryId = entry.id }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LogbookEntryLines(entry = entry, title = entry.date.format(formatter), modifier = Modifier.weight(1f))
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.logbook_edit_title),
                        tint = colors.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (entries.size > SHOWN_ENTRIES) {
                TextButton(onClick = onOpenLogbook) {
                    Text(pluralStringResource(R.plurals.my_canyon_all_outings, entries.size, entries.size))
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

/** "Your times: 4 h 50 on average · sheet: 4 h – 6 h 30", when an outing was timed. */
@Composable
private fun durationComparison(entries: List<LogbookEntry>, topoDuration: DurationRange?): String? {
    val timed = entries.mapNotNull { it.durationMinutes }
    if (timed.isEmpty()) return null
    val average = formatDurationMinutes(timed.average().toInt())
    return if (topoDuration == null) {
        stringResource(R.string.my_canyon_duration, average)
    } else {
        stringResource(R.string.my_canyon_duration_vs_sheet, average, formatDurationRange(topoDuration))
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

private const val SHOWN_ENTRIES = 3
private const val MEMO_COLLAPSED_LINES = 4
private const val REPORT_PROMPT_DAYS = 30L
