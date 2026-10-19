package fr.descentecanyon.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.DurationRange
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.ui.design.DcDatePickerDialog
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.LocalDcShapes
import fr.descentecanyon.app.ui.test.TestTags
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** "45 min", "2 h", "4 h 10", with no-break spaces so that a line never ends inside a duration. */
fun formatDurationMinutes(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$rest\u00A0min"
        rest == 0 -> "$hours\u00A0h"
        else -> String.format(Locale.ROOT, "%d\u00A0h\u00A0%02d", hours, rest)
    }
}

/** "4 h – 6 h 30", or a single value when both ends meet. */
fun formatDurationRange(range: DurationRange): String = if (range.minMinutes == range.maxMinutes) {
    formatDurationMinutes(range.maxMinutes)
} else {
    "${formatDurationMinutes(range.minMinutes)}\u00A0– ${formatDurationMinutes(range.maxMinutes)}"
}

@Composable
fun rememberLogbookDateFormatter(): DateTimeFormatter {
    val locale = LocalLocale.current.platformLocale
    return remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
}

/** What an outing adds to its date: flow seen, duration and companions, on one line. */
@Composable
fun logbookEntryFacts(entry: LogbookEntry): String = listOfNotNull(
    entry.debitLevel?.takeIf { it != NiveauDebit.INCONNU }?.let { debitLevelLabel(it) },
    entry.durationMinutes?.let(::formatDurationMinutes),
    entry.companions?.let { stringResource(R.string.logbook_with, it) },
).joinToString(" · ")

/** An outing in a list: date (or canyon), the facts of the day and the start of the account. */
@Composable
fun LogbookEntryLines(entry: LogbookEntry, title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    val colors = LocalDcColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary) }
        logbookEntryFacts(entry).takeIf { it.isNotEmpty() }?.let { facts ->
            Text(facts, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        entry.note?.let { note ->
            Text(note, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Small "done" mark of a canyon already in the logbook. */
@Composable
fun DescendedBadge(modifier: Modifier = Modifier) {
    val colors = LocalDcColors.current
    val description = stringResource(R.string.logbook_descended_badge_description)
    Surface(
        modifier = modifier,
        shape = LocalDcShapes.current.pill,
        color = colors.forest.copy(alpha = 0.16f),
        contentColor = colors.forest,
        border = BorderStroke(1.dp, colors.forest.copy(alpha = 0.42f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = description, modifier = Modifier.size(13.dp))
            Text(
                stringResource(R.string.logbook_descended_badge),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Adds or edits an outing: date, flow seen, companions (with the names already typed), duration
 * from car to car and account. [initial] is null for a new outing of [canyonId] today.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogbookEntryDialog(
    canyonId: Int,
    canyonName: String,
    initial: LogbookEntry?,
    knownCompanions: List<String>,
    onDismiss: () -> Unit,
    onSave: (LogbookEntry) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val formatter = rememberLogbookDateFormatter()
    val colors = LocalDcColors.current
    var date by rememberSaveable { mutableStateOf((initial?.date ?: LocalDate.now()).toString()) }
    var level by rememberSaveable { mutableStateOf(initial?.debitLevel?.name) }
    var companions by rememberSaveable { mutableStateOf(initial?.companions.orEmpty()) }
    var hours by rememberSaveable { mutableStateOf(initial?.durationMinutes?.let { (it / 60).toString() }.orEmpty()) }
    var minutes by rememberSaveable { mutableStateOf(initial?.durationMinutes?.let { (it % 60).toString() }.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(initial?.note.orEmpty()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selectedDate = LocalDate.parse(date)

    if (showDatePicker) {
        DcDatePickerDialog(
            initialDate = selectedDate,
            onDateSelected = { date = it.toString() },
            onDismiss = { showDatePicker = false },
            maxDate = LocalDate.now(),
        )
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.logbook_delete)) },
            text = { Text("$canyonName · ${selectedDate.format(formatter)}") },
            confirmButton = { TextButton(onClick = onDelete) { Text(stringResource(R.string.logbook_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.logbook_add_title else R.string.logbook_edit_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(canyonName, style = MaterialTheme.typography.titleSmall, color = colors.textSecondary)
                OutlinedButton(onClick = { showDatePicker = true }) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = stringResource(R.string.logbook_change_date))
                    Text(stringResource(R.string.logbook_date, selectedDate.format(formatter)), modifier = Modifier.padding(start = 8.dp))
                }

                DialogLabel(stringResource(R.string.logbook_flow_label))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LOGBOOK_LEVELS.forEach { option ->
                        val color = debitLevelColor(option)
                        FilterChip(
                            selected = level == option.name,
                            // A second tap clears it: the flow is optional.
                            onClick = { level = if (level == option.name) null else option.name },
                            label = { Text(debitLevelShortLabel(option)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = color.copy(alpha = 0.22f),
                                selectedLabelColor = colors.textPrimary,
                            ),
                        )
                    }
                }

                DialogLabel(stringResource(R.string.logbook_companions_label))
                OutlinedTextField(
                    value = companions,
                    onValueChange = { companions = it.take(MAX_COMPANIONS_LENGTH) },
                    placeholder = { Text(stringResource(R.string.logbook_companions_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.LOGBOOK_COMPANIONS),
                    singleLine = true,
                )
                val typed = LogbookEntry.splitCompanions(companions).map { it.lowercase() }.toSet()
                val suggestions = knownCompanions.filterNot { it.lowercase() in typed }.take(MAX_SUGGESTIONS)
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { name ->
                            SuggestionChip(
                                onClick = {
                                    companions = (LogbookEntry.splitCompanions(companions) + name).joinToString(", ")
                                },
                                label = { Text(name) },
                            )
                        }
                    }
                }

                DialogLabel(stringResource(R.string.logbook_duration_label))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hours,
                        onValueChange = { value -> hours = value.filter(Char::isDigit).take(2) },
                        suffix = { Text(stringResource(R.string.logbook_duration_hours)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(96.dp),
                    )
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { value -> minutes = value.filter(Char::isDigit).take(2) },
                        suffix = { Text(stringResource(R.string.logbook_duration_minutes)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(104.dp),
                    )
                }

                DialogLabel(stringResource(R.string.logbook_note_label))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(MAX_NOTE_LENGTH) },
                    placeholder = { Text(stringResource(R.string.logbook_note_hint)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    minLines = 3,
                )
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.logbook_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val duration = (hours.toIntOrNull() ?: 0) * 60 + (minutes.toIntOrNull() ?: 0)
                    val edited = (initial ?: LogbookEntry(canyonId = canyonId, canyonName = canyonName, date = selectedDate)).copy(
                        date = selectedDate,
                        debitLevel = level?.let { name -> NiveauDebit.entries.firstOrNull { it.name == name } },
                        companions = companions.ifBlank { null },
                        durationMinutes = duration.takeIf { it > 0 },
                        note = note.ifBlank { null },
                    )
                    onSave(edited)
                },
                modifier = Modifier.testTag(TestTags.LOGBOOK_SAVE),
            ) {
                Text(stringResource(if (initial == null) R.string.logbook_save else R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Writes or rewrites the memo of a canyon; saving it empty deletes it. */
@Composable
fun CanyonMemoDialog(canyonName: String?, initialText: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.personal_note_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                canyonName?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = LocalDcColors.current.textSecondary) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= MAX_MEMO_LENGTH) text = it },
                    placeholder = { Text(stringResource(R.string.personal_note_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp)
                        .testTag(TestTags.MEMO_FIELD),
                )
                Text(
                    text = stringResource(R.string.personal_note_private),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalDcColors.current.textMuted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, modifier = Modifier.testTag(TestTags.MEMO_SAVE)) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (initialText.isNotBlank()) {
                    TextButton(onClick = { onSave("") }) { Text(stringResource(R.string.personal_note_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun DialogLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = LocalDcColors.current.textSecondary)
}

/** Levels offered for an outing: every level the site uses, driest first. */
private val LOGBOOK_LEVELS = listOf(
    NiveauDebit.SEC,
    NiveauDebit.FILET,
    NiveauDebit.CORRECT,
    NiveauDebit.GROS,
    NiveauDebit.TRES_GROS,
    NiveauDebit.CRUE,
)

private const val MAX_NOTE_LENGTH = 2_000
private const val MAX_MEMO_LENGTH = 2_000
private const val MAX_COMPANIONS_LENGTH = 200
private const val MAX_SUGGESTIONS = 6
