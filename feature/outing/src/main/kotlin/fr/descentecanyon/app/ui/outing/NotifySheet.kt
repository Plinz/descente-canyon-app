package fr.descentecanyon.app.ui.outing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.MessageAudience
import fr.descentecanyon.app.domain.model.MessageSection
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.test.TestTags

/**
 * Telling someone about the outing: a relative who waits for news, the team or accompanied
 * beginners. The user picks whom, reads the whole message and sends it through any messaging app;
 * what it carries and a note can be changed under "Customize".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NotifySheet(
    state: OutingPlanUiState,
    initialAudience: MessageAudience,
    onDismiss: () -> Unit,
    onNoteChanged: (String) -> Unit,
    onMeetAtPlaceChanged: (Boolean) -> Unit,
    onSaveSections: (MessageAudience, Set<MessageSection>) -> Unit,
    onSend: (MessageAudience, String) -> Unit,
) {
    val colors = LocalDcColors.current
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    var audience by rememberSaveable { mutableStateOf(initialAudience) }
    var edited by rememberSaveable(stateSaver = SectionsSaver) { mutableStateOf(emptyMap<MessageAudience, Set<MessageSection>>()) }
    var customizing by rememberSaveable { mutableStateOf(false) }
    val defaults = state.messageSections[audience] ?: audience.defaultSections
    val sections = edited[audience] ?: defaults
    val message = remember(state, audience, sections, resources) {
        outingMessageData(state, resources, locale)?.let {
            buildOutingMessage(ResourceOutingMessageStrings(resources), it, audience, sections)
        }
    }.orEmpty()
    OutingSheet(
        title = stringResource(R.string.outing_notify_title),
        onDismiss = onDismiss,
        modifier = Modifier.testTag(TestTags.OUTING_MESSAGES),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MessageAudience.entries.forEach { option ->
                FilterChip(
                    selected = audience == option,
                    onClick = { audience = option },
                    label = { Text(audienceLabel(option)) },
                    modifier = Modifier.testTag(TestTags.outingAudience(option.name)),
                )
            }
        }
        Text(audienceHint(audience), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Surface(color = colors.surfaceRaised, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp).testTag(TestTags.OUTING_MESSAGE_PREVIEW),
            )
        }
        TextButton(onClick = { customizing = !customizing }) {
            Icon(if (customizing) Icons.Default.ExpandLess else Icons.Default.Tune, contentDescription = null)
            Text(stringResource(R.string.outing_message_customize), modifier = Modifier.padding(start = 8.dp))
        }
        if (customizing) {
            Text(stringResource(R.string.outing_message_sections), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MessageSection.entries.forEach { section ->
                    FilterChip(
                        selected = section in sections,
                        onClick = {
                            val updated = if (section in sections) sections - section else sections + section
                            edited = edited + (audience to updated)
                        },
                        label = { Text(sectionLabel(section)) },
                    )
                }
            }
            if (sections != defaults) {
                TextButton(onClick = { onSaveSections(audience, sections) }) { Text(stringResource(R.string.outing_message_sections_keep)) }
            }
            val place = state.place
            if (place != null && MessageSection.MEETING in sections && audience != MessageAudience.RELATIVE) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !state.choices.meetAtPlace,
                        onClick = { onMeetAtPlaceChanged(false) },
                        label = { Text(stringResource(R.string.outing_meeting_parking)) },
                    )
                    FilterChip(
                        selected = state.choices.meetAtPlace,
                        onClick = { onMeetAtPlaceChanged(true) },
                        label = { Text(stringResource(R.string.outing_meeting_place, place.name)) },
                    )
                }
            }
            OutlinedTextField(
                value = state.choices.note,
                onValueChange = onNoteChanged,
                label = { Text(stringResource(R.string.outing_message_note)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 4,
            )
        }
        Button(
            onClick = { onSend(audience, message) },
            enabled = message.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag(TestTags.OUTING_SHARE_BUTTON),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
            Text(stringResource(sendLabel(audience)), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun audienceLabel(audience: MessageAudience): String = stringResource(
    when (audience) {
        MessageAudience.RELATIVE -> R.string.outing_audience_relative
        MessageAudience.TEAM -> R.string.outing_audience_team
        MessageAudience.GUESTS -> R.string.outing_audience_guests
    },
)

@Composable
private fun audienceHint(audience: MessageAudience): String = stringResource(
    when (audience) {
        MessageAudience.RELATIVE -> R.string.outing_alert_body
        MessageAudience.TEAM -> R.string.outing_audience_team_hint
        MessageAudience.GUESTS -> R.string.outing_audience_guests_hint
    },
)

private fun sendLabel(audience: MessageAudience): Int = when (audience) {
    MessageAudience.RELATIVE -> R.string.outing_share_action
    MessageAudience.TEAM -> R.string.outing_share_team
    MessageAudience.GUESTS -> R.string.outing_share_guests
}

@Composable
private fun sectionLabel(section: MessageSection): String = stringResource(
    when (section) {
        MessageSection.MEETING -> R.string.outing_section_meeting
        MessageSection.SCHEDULE -> R.string.outing_section_schedule
        MessageSection.PLACE -> R.string.outing_section_place
        MessageSection.CANYON -> R.string.outing_section_canyon
        MessageSection.CONDITIONS -> R.string.outing_section_conditions
        MessageSection.GEAR -> R.string.outing_section_gear
        MessageSection.GROUP -> R.string.outing_section_group
        MessageSection.SAFETY -> R.string.outing_section_safety
        MessageSection.SHEET -> R.string.outing_section_sheet
    },
)

/** The sections changed for each kind of message, kept across a rotation. */
private val SectionsSaver = Saver<Map<MessageAudience, Set<MessageSection>>, List<String>>(
    save = { map -> map.map { (audience, sections) -> audience.name + ":" + sections.joinToString(",") { it.name } } },
    restore = { saved ->
        saved.mapNotNull { entry ->
            val audience = MessageAudience.entries.firstOrNull { it.name == entry.substringBefore(":") } ?: return@mapNotNull null
            audience to
                entry.substringAfter(":").split(",").mapNotNull { name -> MessageSection.entries.firstOrNull { it.name == name } }.toSet()
        }.toMap()
    },
)
