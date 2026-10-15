package fr.descentecanyon.app.ui.canyon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.LocalDcColors

/** The user's private note on the canyon, or an invitation to write one. */
@Composable
internal fun PersonalNoteCard(note: String?, onSave: (String) -> Unit, modifier: Modifier = Modifier) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val colors = LocalDcColors.current

    if (editing) {
        PersonalNoteDialog(
            initialText = note.orEmpty(),
            onDismiss = { editing = false },
            onSave = { text ->
                onSave(text)
                editing = false
            },
        )
    }

    DcCard(
        onClick = { editing = true },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        contentPadding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                imageVector = if (note == null) Icons.AutoMirrored.Filled.NoteAdd else Icons.AutoMirrored.Filled.StickyNote2,
                contentDescription = null,
                tint = colors.textSecondary,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(if (note == null) R.string.personal_note_add else R.string.personal_note_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = note ?: stringResource(R.string.personal_note_private),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (note == null) colors.textMuted else colors.textPrimary,
                )
            }
            if (note != null) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.personal_note_edit), tint = colors.textMuted)
            }
        }
    }
}

@Composable
private fun PersonalNoteDialog(initialText: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.personal_note_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= MAX_NOTE_LENGTH) text = it },
                    placeholder = { Text(stringResource(R.string.personal_note_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp),
                )
                Text(
                    text = stringResource(R.string.personal_note_private),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalDcColors.current.textMuted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.save)) }
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

private const val MAX_NOTE_LENGTH = 2_000
