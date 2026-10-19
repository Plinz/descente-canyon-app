package fr.descentecanyon.app.ui.canyon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcDatePickerDialog
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.test.TestTags
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** How many times the user descended this canyon, and a quick way to log a new outing. */
@Composable
internal fun CanyonLogbookCard(
    entries: List<LogbookEntry>,
    onAdd: (LocalDate, String?) -> Unit,
    onReportFlow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalLocale.current.platformLocale)
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showReportPrompt by rememberSaveable { mutableStateOf(false) }
    DcCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(TestTags.DETAIL_LOGBOOK),
        contentPadding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = LocalDcColors.current.textSecondary)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.logbook_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (entries.isEmpty()) {
                        stringResource(R.string.logbook_card_empty)
                    } else {
                        pluralStringResource(R.plurals.logbook_card_count, entries.size, entries.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalDcColors.current.textSecondary,
                )
                // A line of its own, so that next to the add button it does not break mid-phrase.
                entries.firstOrNull()?.let { last ->
                    Text(
                        text = stringResource(R.string.logbook_card_last, last.date.format(formatter)),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalDcColors.current.textSecondary,
                    )
                }
            }
            OutlinedButton(
                onClick = { showAddDialog = true },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.testTag(TestTags.DETAIL_LOGBOOK_ADD),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.logbook_save), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
        }
    }

    if (showAddDialog) {
        AddToLogbookDialog(
            formatter = formatter,
            onDismiss = { showAddDialog = false },
            onConfirm = { date, note ->
                onAdd(date, note)
                showAddDialog = false
                showReportPrompt = true
            },
        )
    }
    if (showReportPrompt) {
        AlertDialog(
            onDismissRequest = { showReportPrompt = false },
            text = { Text(stringResource(R.string.logbook_added_report_prompt)) },
            confirmButton = {
                TextButton(onClick = {
                    showReportPrompt = false
                    onReportFlow()
                }) { Text(stringResource(R.string.outing_report_action)) }
            },
            dismissButton = { TextButton(onClick = { showReportPrompt = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun AddToLogbookDialog(formatter: DateTimeFormatter, onDismiss: () -> Unit, onConfirm: (LocalDate, String?) -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now()) }
    var note by rememberSaveable { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    if (showDatePicker) {
        DcDatePickerDialog(initialDate = date, onDateSelected = {
            date = it
        }, onDismiss = { showDatePicker = false }, maxDate = LocalDate.now())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.logbook_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDatePicker = true }) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = stringResource(R.string.logbook_change_date))
                    Text(stringResource(R.string.logbook_date, date.format(formatter)), modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(MAX_NOTE_LENGTH) },
                    placeholder = { Text(stringResource(R.string.logbook_note_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(date, note.takeIf { it.isNotBlank() }) }, modifier = Modifier.testTag(TestTags.LOGBOOK_SAVE)) {
                Text(stringResource(R.string.logbook_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val MAX_NOTE_LENGTH = 1000
