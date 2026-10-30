package fr.descentecanyon.app.ui.outing

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.AccidentCircumstance
import fr.descentecanyon.app.domain.model.AccidentReport
import fr.descentecanyon.app.domain.model.BreathingTrouble
import fr.descentecanyon.app.domain.model.InjuryArea
import fr.descentecanyon.app.domain.model.Responsiveness
import fr.descentecanyon.app.domain.model.VictimCheck
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcTimePickerDialog
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.test.TestTags
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The questions the rescuers ask, as on the alert sheets of the FFS and FFCAM canyon commissions:
 * when, how many, what happened, the state of the injured person, the water, the exact place.
 * Every answer is optional and goes into the text message of the position.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccidentReportCard(
    state: EmergencyUiState,
    onChange: ((AccidentReport) -> AccidentReport) -> Unit,
    onTimeNow: () -> Unit,
    onTimeSelected: (LocalTime) -> Unit,
) {
    val report = state.report
    val colors = LocalDcColors.current
    // Folded while empty: the screen stays short for the call and the position.
    var open by rememberSaveable { mutableStateOf(false) }
    DcCard(modifier = Modifier.fillMaxWidth().testTag(TestTags.EMERGENCY_REPORT)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle(stringResource(R.string.emergency_report_title))
            Text(stringResource(R.string.emergency_report_intro), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            if (!open && !report.hasAnswers) {
                OutlinedButton(
                    onClick = { open = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(TestTags.EMERGENCY_REPORT_OPEN),
                ) { Text(stringResource(R.string.emergency_report_open)) }
                return@Column
            }

            AccidentTimeRow(time = report.accidentTime, onTimeNow = onTimeNow, onTimeSelected = onTimeSelected)
            CountRow(
                label = stringResource(R.string.emergency_report_injured),
                value = report.injuredCount,
                onValue = { count -> onChange { it.copy(injuredCount = count) } },
            )
            CountRow(
                label = stringResource(R.string.emergency_report_people),
                value = state.peopleOnSite,
                onValue = { count -> onChange { it.copy(peopleOnSite = count) } },
            )

            SectionLabel(stringResource(R.string.emergency_report_circumstances))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccidentCircumstance.entries.forEach { circumstance ->
                    FilterChip(
                        selected = circumstance in report.circumstances,
                        onClick = { onChange { it.copy(circumstances = it.circumstances.toggled(circumstance)) } },
                        label = { Text(stringResource(circumstanceLabel(circumstance))) },
                    )
                }
            }

            SectionLabel(stringResource(R.string.emergency_report_victim))
            YesNoRow(stringResource(R.string.emergency_report_conscious), report.conscious) { value ->
                onChange { it.copy(conscious = value) }
            }
            YesNoRow(stringResource(R.string.emergency_report_breathing), report.breathing) { value ->
                onChange { it.copy(breathing = value) }
            }
            YesNoRow(stringResource(R.string.emergency_report_bleeding), report.heavyBleeding) { value ->
                onChange { it.copy(heavyBleeding = value) }
            }
            YesNoRow(stringResource(R.string.emergency_report_can_walk), report.canWalk) { value ->
                onChange { it.copy(canWalk = value) }
            }

            SectionLabel(stringResource(R.string.emergency_report_injuries))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InjuryArea.entries.forEach { area ->
                    FilterChip(
                        selected = area in report.injuries,
                        onClick = { onChange { it.copy(injuries = it.injuries.toggled(area)) } },
                        label = { Text(stringResource(injuryLabel(area))) },
                    )
                }
            }

            YesNoRow(stringResource(R.string.emergency_report_water_rising), report.waterRising) { value ->
                onChange { it.copy(waterRising = value) }
            }
            OutlinedTextField(
                value = report.location,
                onValueChange = { text -> onChange { it.copy(location = text.take(AccidentReport.MAX_LOCATION_LENGTH)) } },
                label = { Text(stringResource(R.string.emergency_report_location)) },
                placeholder = { Text(stringResource(R.string.emergency_report_location_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AccidentTimeRow(time: Instant?, onTimeNow: () -> Unit, onTimeSelected: (LocalTime) -> Unit) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    var picking by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.emergency_report_time), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = { picking = true }) {
            Text(time?.let { formatClock(it, locale) } ?: stringResource(R.string.emergency_report_time_pick))
        }
        if (time == null) TextButton(onClick = onTimeNow) { Text(stringResource(R.string.emergency_report_time_now)) }
    }
    if (picking) {
        DcTimePickerDialog(
            title = stringResource(R.string.emergency_report_time),
            initialTime = (time ?: Instant.now()).atZone(ZoneId.systemDefault()).toLocalTime(),
            is24Hour = DateFormat.is24HourFormat(context),
            onTimeSelected = onTimeSelected,
            onDismiss = { picking = false },
        )
    }
}

/** A count from 1, unanswered until a button is touched: the first touch of + or - starts at 1. */
@Composable
private fun CountRow(label: String, value: Int?, onValue: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { onValue(((value ?: 2) - 1).coerceAtLeast(1)) }, enabled = value == null || value > 1) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.emergency_report_less, label))
        }
        // Unanswered: a question mark, which a dash would be mistaken for the minus button.
        Text(
            value?.toString() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (value == null) LocalDcColors.current.textMuted else Color.Unspecified,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 28.dp),
        )
        IconButton(onClick = { onValue(((value ?: 0) + 1).coerceAtMost(AccidentReport.MAX_COUNT)) }) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.emergency_report_more, label))
        }
    }
}

/** A yes/no question; touching the chosen answer again unanswers it. */
@Composable
private fun YesNoRow(label: String, value: Boolean?, onValue: (Boolean?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        FilterChip(
            selected = value == true,
            onClick = { onValue(if (value == true) null else true) },
            label = { Text(stringResource(R.string.emergency_report_yes)) },
        )
        FilterChip(
            selected = value == false,
            onClick = { onValue(if (value == false) null else false) },
            label = { Text(stringResource(R.string.emergency_report_no)) },
        )
    }
}

/** What to do until the rescuers arrive, from the FFCAM first aid sheet and the FFS canyon documents. */
@Composable
internal fun WaitingAdviceCard() {
    val colors = LocalDcColors.current
    DcCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(stringResource(R.string.emergency_waiting_title))
            WAITING_ADVICE.forEach { advice ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(advice), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                stringResource(R.string.emergency_waiting_source),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

/** The state of the injured person, noted from time to time: how it changes tells the rescuers a lot. */
@Composable
internal fun VictimFollowUpCard(
    checks: List<VictimCheck>,
    onAdd: (VictimCheck) -> Unit,
    onRemove: (Instant) -> Unit,
    onShare: () -> Unit,
) {
    val colors = LocalDcColors.current
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    var adding by rememberSaveable { mutableStateOf(false) }
    DcCard(modifier = Modifier.fillMaxWidth().testTag(TestTags.EMERGENCY_FOLLOW_UP)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(stringResource(R.string.emergency_followup_title))
            Text(
                stringResource(R.string.emergency_followup_intro),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            checks.sortedByDescending { it.at }.forEach { check ->
                val time = formatClock(check.at, locale)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(time, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        checkDetails(resources, check).takeIf { it.isNotEmpty() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    IconButton(onClick = { onRemove(check.at) }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.emergency_followup_delete, time),
                            tint = colors.textMuted,
                        )
                    }
                }
            }
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(stringResource(R.string.emergency_followup_add))
            }
            if (checks.isNotEmpty()) {
                TextButton(onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.emergency_followup_share), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
    if (adding) {
        VictimCheckDialog(
            onDismiss = { adding = false },
            onSave = {
                onAdd(it)
                adding = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VictimCheckDialog(onDismiss: () -> Unit, onSave: (VictimCheck) -> Unit) {
    var responds by rememberSaveable { mutableStateOf<Responsiveness?>(null) }
    var breathing by rememberSaveable { mutableStateOf<BreathingTrouble?>(null) }
    var pain by rememberSaveable { mutableStateOf<Int?>(null) }
    var cold by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.emergency_followup_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.emergency_followup_responds))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Responsiveness.entries.forEach { option ->
                        FilterChip(
                            selected = responds == option,
                            onClick = { responds = option.takeUnless { responds == it } },
                            label = { Text(stringResource(responsivenessLabel(option))) },
                        )
                    }
                }
                SectionLabel(stringResource(R.string.emergency_followup_breathing))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BreathingTrouble.entries.forEach { option ->
                        FilterChip(
                            selected = breathing == option,
                            onClick = { breathing = option.takeUnless { breathing == it } },
                            label = { Text(stringResource(breathingLabel(option))) },
                        )
                    }
                }
                SectionLabel(stringResource(R.string.emergency_followup_pain))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    VictimCheck.PAIN_RANGE.forEach { level ->
                        FilterChip(
                            selected = pain == level,
                            onClick = { pain = level.takeUnless { pain == it } },
                            label = { Text(level.toString()) },
                        )
                    }
                }
                YesNoRow(stringResource(R.string.emergency_followup_cold), cold) { cold = it }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(AccidentReport.MAX_NOTE_LENGTH) },
                    label = { Text(stringResource(R.string.emergency_followup_note)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    VictimCheck(
                        at = Instant.EPOCH,
                        responds = responds,
                        breathing = breathing,
                        pain = pain,
                        cold = cold,
                        note = note.trim(),
                    ),
                )
            }) { Text(stringResource(R.string.emergency_followup_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Forgets the report once the accident is over, after a confirmation. */
@Composable
internal fun ClearReportButton(onClear: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(onClick = { confirming = true }) { Text(stringResource(R.string.emergency_report_clear)) }
        Text(
            stringResource(R.string.emergency_report_kept),
            style = MaterialTheme.typography.bodySmall,
            color = LocalDcColors.current.textSecondary,
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            text = { Text(stringResource(R.string.emergency_report_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onClear()
                }) { Text(stringResource(R.string.emergency_report_clear_action)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

/** The lines of the report for the text message, only what was answered. */
internal fun reportMessageLines(resources: Resources, report: AccidentReport, peopleOnSite: Int?, locale: Locale): List<String> {
    val state = listOfNotNull(
        report.conscious?.let { resources.getString(if (it) R.string.emergency_state_conscious else R.string.emergency_state_unconscious) },
        report.breathing?.let {
            resources.getString(if (it) R.string.emergency_state_breathing else R.string.emergency_state_not_breathing)
        },
        report.heavyBleeding?.let {
            resources.getString(if (it) R.string.emergency_state_bleeding else R.string.emergency_state_not_bleeding)
        },
        report.canWalk?.let { resources.getString(if (it) R.string.emergency_state_can_walk else R.string.emergency_state_cannot_walk) },
    )
    return listOfNotNull(
        report.accidentTime?.let { resources.getString(R.string.emergency_sms_time, formatClock(it, locale)) },
        report.injuredCount?.let { resources.getString(R.string.emergency_sms_injured, it) },
        peopleOnSite?.let { resources.getString(R.string.emergency_sms_people, it) },
        report.circumstances.takeIf { it.isNotEmpty() }?.let { circumstances ->
            resources.getString(
                R.string.emergency_sms_circumstances,
                AccidentCircumstance.entries.filter {
                    it in circumstances
                }.joinToString(", ") { resources.getString(circumstanceLabel(it)) },
            )
        },
        state.takeIf { it.isNotEmpty() }?.let { resources.getString(R.string.emergency_sms_state, it.joinToString(", ")) },
        report.injuries.takeIf { it.isNotEmpty() }?.let { injuries ->
            resources.getString(
                R.string.emergency_sms_injuries,
                InjuryArea.entries.filter { it in injuries }.joinToString(", ") { resources.getString(injuryLabel(it)) },
            )
        },
        report.waterRising?.let {
            resources.getString(if (it) R.string.emergency_sms_water_rising else R.string.emergency_sms_water_steady)
        },
        report.location.trim().takeIf { it.isNotEmpty() }?.let { resources.getString(R.string.emergency_sms_location, it) },
    )
}

/** The follow-up to send to the rescuers or a relative, oldest look first. */
internal fun followUpMessage(resources: Resources, checks: List<VictimCheck>, canyonName: String?, locale: Locale): String = (
    listOf(
        canyonName?.let { resources.getString(R.string.emergency_followup_share_header_canyon, it) }
            ?: resources.getString(R.string.emergency_followup_share_header),
    ) +
        checks.sortedBy { it.at }.map { check ->
            listOf(formatClock(check.at, locale), checkDetails(resources, check)).filter { it.isNotEmpty() }.joinToString(" : ")
        }
    ).joinToString("\n")

/** "répond : confuse · gêne pour respirer : un peu · douleur 6/10 · a froid · note". */
private fun checkDetails(resources: Resources, check: VictimCheck): String = listOfNotNull(
    check.responds?.let { resources.getString(R.string.emergency_followup_line_responds, resources.inLine(responsivenessLabel(it))) },
    check.breathing?.let { resources.getString(R.string.emergency_followup_line_breathing, resources.inLine(breathingLabel(it))) },
    check.pain?.let { resources.getString(R.string.emergency_followup_line_pain, it) },
    check.cold?.let { resources.getString(if (it) R.string.emergency_followup_line_cold else R.string.emergency_followup_line_warm) },
    check.note.takeIf { it.isNotBlank() },
).joinToString(" · ")

/** A chip label inside a sentence: "Confuse" read as "confuse". */
private fun Resources.inLine(@StringRes label: Int): String {
    val locale = configuration.locales[0] ?: Locale.ROOT
    return getString(label).replaceFirstChar { it.lowercase(locale) }
}

private fun formatClock(instant: Instant, locale: Locale): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(instant.atZone(ZoneId.systemDefault()))

private fun <T> Set<T>.toggled(value: T): Set<T> = if (value in this) this - value else this + value

@StringRes
private fun circumstanceLabel(circumstance: AccidentCircumstance): Int = when (circumstance) {
    AccidentCircumstance.FALL -> R.string.emergency_circumstance_fall
    AccidentCircumstance.JUMP -> R.string.emergency_circumstance_jump
    AccidentCircumstance.SLIDE -> R.string.emergency_circumstance_slide
    AccidentCircumstance.STUCK_ON_ROPE -> R.string.emergency_circumstance_stuck_on_rope
    AccidentCircumstance.DROWNING -> R.string.emergency_circumstance_drowning
    AccidentCircumstance.ROCKFALL -> R.string.emergency_circumstance_rockfall
    AccidentCircumstance.FLOOD -> R.string.emergency_circumstance_flood
    AccidentCircumstance.COLD_EXHAUSTION -> R.string.emergency_circumstance_cold
    AccidentCircumstance.ILLNESS -> R.string.emergency_circumstance_illness
}

@StringRes
private fun injuryLabel(area: InjuryArea): Int = when (area) {
    InjuryArea.HEAD -> R.string.emergency_injury_head
    InjuryArea.BACK_NECK -> R.string.emergency_injury_back_neck
    InjuryArea.ARM_SHOULDER -> R.string.emergency_injury_arm
    InjuryArea.LEG_ANKLE -> R.string.emergency_injury_leg
    InjuryArea.BLEEDING_WOUND -> R.string.emergency_injury_bleeding
}

@StringRes
private fun responsivenessLabel(value: Responsiveness): Int = when (value) {
    Responsiveness.NORMAL -> R.string.emergency_followup_responds_normal
    Responsiveness.CONFUSED -> R.string.emergency_followup_responds_confused
    Responsiveness.NONE -> R.string.emergency_followup_responds_none
}

@StringRes
private fun breathingLabel(value: BreathingTrouble): Int = when (value) {
    BreathingTrouble.NONE -> R.string.emergency_followup_breathing_none
    BreathingTrouble.SOME -> R.string.emergency_followup_breathing_some
    BreathingTrouble.SEVERE -> R.string.emergency_followup_breathing_severe
}

/** Most urgent first. */
private val WAITING_ADVICE = listOf(
    R.string.emergency_waiting_safety,
    R.string.emergency_waiting_bleeding,
    R.string.emergency_waiting_unconscious,
    R.string.emergency_waiting_spine,
    R.string.emergency_waiting_cold,
    R.string.emergency_waiting_harness,
    R.string.emergency_waiting_drowning,
    R.string.emergency_waiting_meeting,
)
