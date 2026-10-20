package fr.descentecanyon.app.ui.outing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import fr.descentecanyon.app.domain.model.DaylightCheck
import fr.descentecanyon.app.domain.model.GroupLevel
import fr.descentecanyon.app.domain.model.OutingAnchor
import fr.descentecanyon.app.domain.model.OutingChoices
import fr.descentecanyon.app.domain.model.OutingPhase
import fr.descentecanyon.app.domain.model.TimeSpan
import fr.descentecanyon.app.domain.model.TimeWindow
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.test.TestTags
import java.time.format.DateTimeFormatter

/** One line of the day: a moment, or the step that leads to the next moment. */
private sealed interface DayLine {
    data class Moment(val label: String, val window: TimeWindow?, val emphasized: Boolean = false) : DayLine
    data class Step(val label: String, val span: TimeSpan?, val phase: OutingPhase? = null) : DayLine
}

@Composable
internal fun TimelineCard(
    state: OutingPlanUiState,
    timeFormatter: DateTimeFormatter,
    onAdjust: (OutingPhase, Int) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val schedule = state.schedule ?: return
    val colors = LocalDcColors.current
    var adjusting by remember { mutableStateOf<OutingPhase?>(null) }
    adjusting?.let { phase ->
        AdjustPhaseDialog(
            phase = phase,
            span = schedule.spans[phase],
            adjustment = state.choices.adjustments[phase] ?: 0,
            onAdjust = { onAdjust(phase, it) },
            onDismiss = { adjusting = null },
        )
    }
    val place = state.place?.takeIf { schedule.leavePlace != null }
    val drive = schedule.driveMinutes?.toLong()?.let(TimeSpan::exactly)
    val shuttleKm = state.sheetShuttle?.takeIf { state.withShuttle }?.distanceKm?.toInt()?.coerceAtLeast(1)
    val lines = buildList {
        if (place != null) {
            add(DayLine.Moment(stringResource(R.string.outing_step_leave_place, place.name), TimeWindow.at(schedule.leavePlace!!)))
            add(DayLine.Step(stringResource(R.string.outing_step_drive), drive))
        }
        if (shuttleKm != null) {
            add(DayLine.Moment(stringResource(R.string.outing_step_meeting_downstream), TimeWindow.at(schedule.arriveParking)))
            add(
                DayLine.Step(
                    stringResource(R.string.outing_step_shuttle, shuttleKm),
                    schedule.spans[OutingPhase.SHUTTLE],
                    OutingPhase.SHUTTLE,
                ),
            )
            add(DayLine.Moment(stringResource(R.string.outing_step_start_parking), TimeWindow.at(schedule.arriveStartParking)))
        } else {
            add(DayLine.Moment(stringResource(R.string.outing_step_arrive_parking), TimeWindow.at(schedule.arriveParking)))
        }
        add(DayLine.Step(stringResource(R.string.outing_step_prep), schedule.spans[OutingPhase.PREP], OutingPhase.PREP))
        add(DayLine.Moment(stringResource(R.string.outing_step_start), TimeWindow.at(schedule.walkStart)))
        add(DayLine.Step(stringResource(R.string.outing_step_approach), schedule.spans[OutingPhase.APPROACH], OutingPhase.APPROACH))
        add(DayLine.Moment(stringResource(R.string.outing_step_descent_start), schedule.descentStart))
        add(DayLine.Step(stringResource(R.string.outing_step_descent), schedule.spans[OutingPhase.DESCENT], OutingPhase.DESCENT))
        add(DayLine.Moment(stringResource(R.string.outing_step_descent_end), schedule.descentEnd))
        add(DayLine.Step(stringResource(R.string.outing_step_return), schedule.spans[OutingPhase.RETURN], OutingPhase.RETURN))
        add(DayLine.Moment(stringResource(R.string.outing_step_back), schedule.backAtCar, emphasized = true))
        add(
            DayLine.Step(
                stringResource(if (shuttleKm != null) R.string.outing_step_pack_up_shuttle else R.string.outing_step_pack_up),
                schedule.spans[OutingPhase.PACK_UP]?.let { packUp -> schedule.shuttleBack?.let { packUp + it } ?: packUp },
                OutingPhase.PACK_UP,
            ),
        )
        add(DayLine.Moment(stringResource(R.string.outing_step_leave_parking), schedule.readyToLeave))
        if (place != null) {
            add(DayLine.Step(stringResource(R.string.outing_step_drive), drive))
            add(DayLine.Moment(stringResource(R.string.outing_step_back_place, place.name), schedule.backAtPlace))
        }
    }
    DcCard(modifier = Modifier.fillMaxWidth().testTag(TestTags.OUTING_TIMELINE)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.outing_timeline_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            lines.forEach { line ->
                when (line) {
                    is DayLine.Moment -> MomentRow(line, timeFormatter)

                    is DayLine.Step -> StepRow(
                        line = line,
                        adjustment = line.phase?.let { state.choices.adjustments[it] } ?: 0,
                        onClick = line.phase?.takeIf { line.span != null }?.let { phase -> { adjusting = phase } },
                    )
                }
            }
            Text(
                text = stringResource(
                    R.string.outing_durations_source,
                    durationLabel(state.approach),
                    durationLabel(state.descent),
                    durationLabel(state.back),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
            PaceBreakdown(state)
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.outing_adjust_coefficients))
            }
            DaylightLine(state, timeFormatter)
        }
    }
}

@Composable
private fun MomentRow(line: DayLine.Moment, timeFormatter: DateTimeFormatter) {
    val window = line.window
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = window?.estimate?.toLocalTime()?.format(timeFormatter) ?: "--:--",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (line.emphasized) FontWeight.Bold else FontWeight.SemiBold,
            modifier = Modifier.width(64.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                line.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (line.emphasized) FontWeight.Bold else FontWeight.Normal,
            )
            when {
                window == null -> Text(
                    stringResource(R.string.outing_step_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalDcColors.current.textMuted,
                )

                window.earliest != window.latest -> Text(
                    stringResource(
                        R.string.outing_window,
                        window.earliest.toLocalTime().format(timeFormatter),
                        window.latest.toLocalTime().format(timeFormatter),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalDcColors.current.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun StepRow(line: DayLine.Step, adjustment: Int, onClick: (() -> Unit)?) {
    val colors = LocalDcColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 64.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val duration = line.span?.let { formatMinutes(it.likely) } ?: stringResource(R.string.duration_unknown)
        val text = if (adjustment != 0) {
            stringResource(R.string.outing_step_adjusted, line.label, duration, signedMinutes(adjustment))
        } else {
            stringResource(R.string.outing_step_duration, line.label, duration)
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.weight(1f))
        if (onClick != null) {
            Icon(
                Icons.Default.Edit,
                contentDescription = stringResource(R.string.outing_adjust_step),
                tint = colors.textMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** "+30 min", "−15 min". */
internal fun signedMinutes(minutes: Int): String = (if (minutes > 0) "+" else "−") + formatMinutes(kotlin.math.abs(minutes).toLong())

/** Where the times of the group come from: its coefficients and the user's own pace. */
@Composable
private fun PaceBreakdown(state: OutingPlanUiState) {
    val pace = state.pace
    val locale = LocalLocale.current.platformLocale
    val colors = LocalDcColors.current
    val parts = buildList {
        if (pace.groupFactor != 1.0) {
            add(pluralStringResource(R.plurals.outing_pace_group, pace.groupSize, pace.groupSize, formatFactor(pace.groupFactor, locale)))
        }
        if (pace.levelFactor != 1.0) {
            add(
                stringResource(
                    if (pace.level == GroupLevel.DISCOVERY) R.string.outing_pace_discovery else R.string.outing_pace_beginners,
                    formatFactor(pace.levelFactor, locale),
                ),
            )
        }
    }
    if (parts.isNotEmpty()) {
        Text(
            stringResource(
                R.string.outing_pace_descent,
                formatFactor(pace.groupFactor * pace.levelFactor, locale),
                parts.joinToString(", "),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
        )
    }
    pace.personalPace?.let { personal ->
        Text(
            pluralStringResource(R.plurals.outing_pace_personal, personal.outings, formatFactor(personal.ratio, locale), personal.outings),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
        )
    }
}

@Composable
private fun DaylightLine(state: OutingPlanUiState, timeFormatter: DateTimeFormatter) {
    val colors = LocalDcColors.current
    val daylight = state.daylight
    val anchorIsPlace = state.schedule?.anchor == OutingAnchor.LEAVE_PLACE
    val lines: List<Pair<String, androidx.compose.ui.graphics.Color>> = when (daylight) {
        is DaylightCheck.Ok -> listOfNotNull(
            stringResource(R.string.outing_daylight_ok, formatDuration(daylight.margin), daylight.sunset.format(timeFormatter)) to
                colors.textSecondary,
            daylight.latestDescentStart?.let {
                stringResource(R.string.outing_daylight_turn_around, it.format(timeFormatter)) to colors.textSecondary
            },
        )

        is DaylightCheck.Late -> {
            val latest = daylight.latestAnchor
            listOf(
                when {
                    latest == null -> stringResource(R.string.outing_daylight_too_long, daylight.sunset.format(timeFormatter))

                    anchorIsPlace -> stringResource(
                        R.string.outing_daylight_late_place,
                        daylight.sunset.format(timeFormatter),
                        state.place?.name.orEmpty(),
                        latest.format(timeFormatter),
                    )

                    else -> stringResource(
                        R.string.outing_daylight_late,
                        daylight.sunset.format(timeFormatter),
                        latest.format(timeFormatter),
                    )
                } to MaterialTheme.colorScheme.error,
            )
        }

        DaylightCheck.Unknown -> listOf(stringResource(R.string.outing_daylight_unknown) to colors.textMuted)
    }
    lines.forEachIndexed { index, (text, color) ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when {
                    daylight is DaylightCheck.Late -> Icons.Default.WarningAmber
                    index > 0 -> Icons.Default.Flag
                    else -> Icons.Default.WbTwilight
                },
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = color,
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                modifier = if (index == 0) Modifier.testTag(TestTags.OUTING_DAYLIGHT) else Modifier,
            )
        }
    }
}

@Composable
private fun AdjustPhaseDialog(phase: OutingPhase, span: TimeSpan?, adjustment: Int, onAdjust: (Int) -> Unit, onDismiss: () -> Unit) {
    val step = OutingChoices.ADJUSTMENT_STEP_MINUTES
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(phaseLabel(phase)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                span?.let {
                    Text(stringResource(R.string.outing_adjust_current, spanLabel(it)), style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { onAdjust(adjustment - step) }) { Text("−$step") }
                    Text(
                        if (adjustment == 0) stringResource(R.string.outing_adjust_none) else signedMinutes(adjustment),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedButton(onClick = { onAdjust(adjustment + step) }) { Text("+$step") }
                }
                Text(
                    stringResource(R.string.outing_adjust_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalDcColors.current.textSecondary,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = {
            if (adjustment != 0) TextButton(onClick = { onAdjust(0) }) { Text(stringResource(R.string.outing_adjust_reset)) }
        },
    )
}

@Composable
private fun phaseLabel(phase: OutingPhase): String = stringResource(
    when (phase) {
        OutingPhase.SHUTTLE -> R.string.outing_phase_shuttle
        OutingPhase.PREP -> R.string.outing_step_prep
        OutingPhase.APPROACH -> R.string.outing_step_approach
        OutingPhase.DESCENT -> R.string.outing_step_descent
        OutingPhase.RETURN -> R.string.outing_step_return
        OutingPhase.PACK_UP -> R.string.outing_step_pack_up
    },
)
