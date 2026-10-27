package fr.descentecanyon.app.ui.outing

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.DaylightCheck
import fr.descentecanyon.app.domain.model.DriveTime
import fr.descentecanyon.app.domain.model.DriveTimeSource
import fr.descentecanyon.app.domain.model.GroupLevel
import fr.descentecanyon.app.domain.model.OutingAnchor
import fr.descentecanyon.app.domain.model.OutingChoices
import fr.descentecanyon.app.domain.model.OutingSettings
import fr.descentecanyon.app.domain.model.TimeWindow
import fr.descentecanyon.app.domain.model.WeatherCondition
import fr.descentecanyon.app.ui.common.openUrl
import fr.descentecanyon.app.ui.common.startActivitySafely
import fr.descentecanyon.app.ui.components.EmergencyIconButton
import fr.descentecanyon.app.ui.components.debitLevelLabel
import fr.descentecanyon.app.ui.components.debitRangeLabel
import fr.descentecanyon.app.ui.components.relativeDayLabel
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcCardVariant
import fr.descentecanyon.app.ui.design.DcDropdownMenu
import fr.descentecanyon.app.ui.design.DcDropdownMenuItem
import fr.descentecanyon.app.ui.design.DcTimePickerDialog
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.test.TestTags
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A bottom sheet of the plan, styled as the pickers of the app; its content scrolls when long. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OutingSheet(title: String, onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalDcColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
        containerColor = colors.backgroundElevated,
        contentColor = colors.textPrimary,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.textMuted.copy(alpha = 0.4f)) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

/** The three choices of the plan, one line each: the day, the departure and the group; a tap opens each. */
@Composable
internal fun PlanSettingsCard(state: OutingPlanUiState, onDayClick: () -> Unit, onDepartureClick: () -> Unit, onGroupClick: () -> Unit) {
    val colors = LocalDcColors.current
    DcCard(modifier = Modifier.fillMaxWidth(), contentPadding = 4.dp) {
        SettingRow(
            icon = Icons.Default.CalendarMonth,
            label = stringResource(R.string.outing_date_label),
            value = dayLabel(state),
            onClick = onDayClick,
            modifier = Modifier.testTag(TestTags.OUTING_DAY_ROW),
        )
        HorizontalDivider(color = colors.borderSubtle, modifier = Modifier.padding(horizontal = 12.dp))
        SettingRow(
            icon = Icons.Default.DirectionsCar,
            label = stringResource(R.string.outing_departure_label),
            value = departureLabel(state),
            onClick = onDepartureClick,
            modifier = Modifier.testTag(TestTags.OUTING_DEPARTURE_ROW),
        )
        HorizontalDivider(color = colors.borderSubtle, modifier = Modifier.padding(horizontal = 12.dp))
        SettingRow(
            icon = Icons.Default.Groups,
            label = stringResource(R.string.outing_group_title),
            value = groupLabel(state),
            onClick = onGroupClick,
            modifier = Modifier.testTag(TestTags.OUTING_GROUP_ROW),
        )
    }
}

@Composable
private fun SettingRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDcColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.textSecondary)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textMuted)
    }
}

/** "Demain · mardi 20 octobre 2026". */
@Composable
private fun dayLabel(state: OutingPlanUiState): String {
    val locale = LocalLocale.current.platformLocale
    val date = state.choices.date
    val full = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date)
    return when (date) {
        state.today -> stringResource(R.string.outing_day_relative, stringResource(R.string.outing_day_today), full)
        state.today.plusDays(1) -> stringResource(R.string.outing_day_relative, stringResource(R.string.outing_day_tomorrow), full)
        else -> full.replaceFirstChar { it.titlecase(locale) }
    }
}

/** "08:30 · départ de Camping du Roya", or "09:00 · arrivée au parking". */
@Composable
private fun departureLabel(state: OutingPlanUiState): String {
    val time = state.choices.anchorTime.format(rememberTimeFormatter())
    val place = state.place
    val fromPlace = place != null && (state.schedule?.anchor ?: state.choices.anchor) == OutingAnchor.LEAVE_PLACE
    return if (fromPlace) {
        stringResource(R.string.outing_departure_value_place, time, place.name)
    } else {
        stringResource(R.string.outing_departure_value_parking, time)
    }
}

/** "4 personnes · Autonome · navette 6 km". */
@Composable
private fun groupLabel(state: OutingPlanUiState): String {
    val size = state.choices.groupSize
    return listOfNotNull(
        pluralStringResource(R.plurals.outing_group_size, size, size),
        groupLevelLabel(state.choices.groupLevel),
        state.sheetShuttle?.takeIf { state.withShuttle }?.let {
            stringResource(R.string.outing_shuttle_short, it.distanceKm.toInt().coerceAtLeast(1))
        },
    ).joinToString(" · ")
}

/** Where the day starts from and when: the place (or none), the time and the drive to the parking. */
@Composable
internal fun DepartureSheet(
    state: OutingPlanUiState,
    onDismiss: () -> Unit,
    onPlaceSelected: (Long?) -> Unit,
    onTimeSelected: (java.time.LocalTime) -> Unit,
    onManagePlaces: () -> Unit,
    onSetManualDrive: (Int?) -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalDcColors.current
    var showTimePicker by remember { mutableStateOf(false) }
    val place = state.place
    val timeLabel = if (place != null) {
        stringResource(R.string.outing_anchor_leave_place, place.name)
    } else {
        stringResource(R.string.outing_anchor_arrive_parking)
    }
    if (showTimePicker) {
        DcTimePickerDialog(
            title = timeLabel,
            initialTime = state.choices.anchorTime,
            is24Hour = DateFormat.is24HourFormat(context),
            onTimeSelected = onTimeSelected,
            onDismiss = { showTimePicker = false },
        )
    }
    OutingSheet(title = stringResource(R.string.outing_departure_label), onDismiss = onDismiss) {
        Text(stringResource(R.string.outing_place_label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Column {
            PlaceOption(label = stringResource(R.string.outing_place_none), selected = place == null, onClick = { onPlaceSelected(null) })
            state.places.forEach { option ->
                PlaceOption(label = option.name, selected = option.id == place?.id, onClick = { onPlaceSelected(option.id) })
            }
        }
        if (state.places.isEmpty()) {
            Text(stringResource(R.string.outing_place_hint), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        TextButton(onClick = onManagePlaces) {
            Icon(if (state.places.isEmpty()) Icons.Default.Add else Icons.Default.Edit, contentDescription = null)
            Text(
                stringResource(if (state.places.isEmpty()) R.string.places_add else R.string.places_manage),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        HorizontalDivider(color = colors.borderSubtle)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.testTag(TestTags.OUTING_START_TIME)) {
                Icon(Icons.Default.AccessTime, contentDescription = stringResource(R.string.outing_change_time))
                Text(state.choices.anchorTime.format(rememberTimeFormatter()), modifier = Modifier.padding(start = 8.dp))
            }
            Text(timeLabel, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        }
        if (place != null) DriveLine(state = state, onSetManualDrive = onSetManualDrive)
    }
}

@Composable
internal fun PlaceOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The drive to the parking, where its time comes from, the user's own time and the way there in a navigation app. */
@Composable
internal fun DriveLine(state: OutingPlanUiState, onSetManualDrive: ((Int?) -> Unit)?) {
    val context = LocalContext.current
    val colors = LocalDcColors.current
    var editing by remember { mutableStateOf(false) }
    val drive = state.drive
    if (editing && onSetManualDrive != null) {
        ManualDriveDialog(
            current = drive,
            onSave = {
                editing = false
                onSetManualDrive(it)
            },
            onDismiss = { editing = false },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when {
                    drive == null -> stringResource(R.string.outing_drive_loading)
                    state.meetingParking == null -> stringResource(R.string.outing_drive_no_parking)
                    else -> driveText(drive)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).testTag(TestTags.OUTING_DRIVE),
            )
            val point = state.meetingParking
            if (point != null && onSetManualDrive != null) {
                IconButton(onClick = { editing = true }) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.outing_drive_edit), tint = colors.textSecondary)
                }
                IconButton(onClick = {
                    context.startActivitySafely(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            "geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}".toUri(),
                        ),
                    )
                }) {
                    Icon(
                        Icons.Default.Directions,
                        contentDescription = stringResource(R.string.outing_drive_directions),
                        tint = colors.textSecondary,
                    )
                }
            }
        }
        if (drive?.source == DriveTimeSource.ROUTE) {
            Text(
                text = stringResource(R.string.outing_drive_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { context.openUrl(FIX_THE_MAP_URL) },
            )
        }
    }
}

@Composable
private fun driveText(drive: DriveTime): String {
    val duration = formatMinutes(drive.minutes.toLong())
    return when (drive.source) {
        DriveTimeSource.ROUTE -> drive.distanceKm?.let { stringResource(R.string.outing_drive_route, duration, formatKm(it)) }
            ?: stringResource(R.string.outing_drive_manual, duration)

        DriveTimeSource.MANUAL -> stringResource(R.string.outing_drive_manual, duration)

        DriveTimeSource.ESTIMATE -> stringResource(R.string.outing_drive_estimate, duration)
    }
}

@Composable
private fun ManualDriveDialog(current: DriveTime?, onSave: (Int?) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current?.minutes?.toString().orEmpty()) }
    val minutes = text.toIntOrNull()?.takeIf { it in DriveTime.MANUAL_RANGE }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.outing_drive_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.outing_drive_edit_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { value -> text = value.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.outing_drive_minutes)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(minutes) }, enabled = minutes != null) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                if (current?.source == DriveTimeSource.MANUAL) {
                    TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.outing_drive_use_route)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** The size and level of the group, and the shuttle when the sheet asks for one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GroupSheet(
    state: OutingPlanUiState,
    onDismiss: () -> Unit,
    onSizeSelected: (Int) -> Unit,
    onLevelSelected: (GroupLevel) -> Unit,
    onShuttleSelected: (Boolean) -> Unit,
) {
    val size = state.choices.groupSize
    OutingSheet(title = stringResource(R.string.outing_group_title), onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Groups, contentDescription = null, tint = LocalDcColors.current.textSecondary)
            IconButton(onClick = { onSizeSelected(size - 1) }, enabled = size > OutingSettings.GROUP_SIZE_RANGE.first) {
                Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.outing_group_less))
            }
            Text(
                pluralStringResource(R.plurals.outing_group_size, size, size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(TestTags.OUTING_GROUP_SIZE),
            )
            IconButton(onClick = { onSizeSelected(size + 1) }, enabled = size < OutingSettings.GROUP_SIZE_RANGE.last) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.outing_group_more))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GroupLevel.entries.forEach { level ->
                FilterChip(
                    selected = state.choices.groupLevel == level,
                    onClick = { onLevelSelected(level) },
                    label = { Text(groupLevelLabel(level)) },
                )
            }
        }
        state.sheetShuttle?.let { shuttle ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.outing_shuttle_title, shuttle.distanceKm.toInt().coerceAtLeast(1)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.outing_shuttle_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalDcColors.current.textSecondary,
                    )
                }
                Switch(checked = state.withShuttle, onCheckedChange = onShuttleSelected)
            }
        }
    }
}

@Composable
internal fun groupLevelLabel(level: GroupLevel): String = stringResource(
    when (level) {
        GroupLevel.AUTONOMOUS -> R.string.outing_level_autonomous
        GroupLevel.WITH_BEGINNERS -> R.string.outing_level_beginners
        GroupLevel.DISCOVERY -> R.string.outing_level_discovery
    },
)

/**
 * The answer first: when the group leaves, starts the descent and is back, when the relative
 * worries, then what may call the outing off (storm, night, flow).
 */
@Composable
internal fun SummaryCard(state: OutingPlanUiState, timeFormatter: DateTimeFormatter, onAlertClick: () -> Unit) {
    val schedule = state.schedule ?: return
    val colors = LocalDcColors.current
    val locale = LocalLocale.current.platformLocale
    DcCard(modifier = Modifier.fillMaxWidth().testTag(TestTags.OUTING_SUMMARY)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.outing_summary_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val place = state.place?.takeIf { schedule.leavePlace != null }
            if (place != null) {
                KeyTimeRow(
                    stringResource(R.string.outing_step_leave_place, place.name),
                    schedule.leavePlace?.let(TimeWindow::at),
                    timeFormatter,
                )
            } else {
                val meeting = if (state.withShuttle) R.string.outing_step_meeting_downstream else R.string.outing_step_arrive_parking
                KeyTimeRow(stringResource(meeting), TimeWindow.at(schedule.arriveParking), timeFormatter)
            }
            KeyTimeRow(stringResource(R.string.outing_step_descent_start), schedule.descentStart, timeFormatter, showRange = false)
            KeyTimeRow(stringResource(R.string.outing_step_back), schedule.backAtCar, timeFormatter, emphasized = true)
            state.alertAt?.let { alertAt ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onAlertClick)
                        .testTag(TestTags.OUTING_ALERT_ROW),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        alertAt.toLocalTime().format(timeFormatter),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.width(TIME_COLUMN),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.outing_summary_alert),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            alertDetail(state),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.outing_alert_margin),
                        tint = colors.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            HorizontalDivider(color = colors.borderSubtle)
            Signals(state, timeFormatter)
        }
    }
}

/** "retour au plus tard + 2 h", with its day when it is another one, the delay given during the outing or the full day assumed. */
@Composable
private fun alertDetail(state: OutingPlanUiState): String = buildList {
    val locale = LocalLocale.current.platformLocale
    state.alertAt?.toLocalDate()?.takeIf { it != state.choices.date }?.let { day ->
        add(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(day))
    }
    add(
        if (state.schedule?.backAtCar == null) {
            stringResource(R.string.outing_summary_alert_full_day, state.choices.alertMarginHours)
        } else {
            stringResource(R.string.outing_summary_alert_margin, state.choices.alertMarginHours)
        },
    )
    if (state.choices.alertDelayMinutes > 0) {
        add(stringResource(R.string.outing_summary_alert_delayed, formatMinutes(state.choices.alertDelayMinutes.toLong())))
    }
}.joinToString(" · ")

@Composable
private fun KeyTimeRow(
    label: String,
    window: TimeWindow?,
    timeFormatter: DateTimeFormatter,
    emphasized: Boolean = false,
    showRange: Boolean = true,
) {
    val colors = LocalDcColors.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = window?.estimate?.toLocalTime()?.format(timeFormatter) ?: "--:--",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.SemiBold,
            modifier = Modifier.width(TIME_COLUMN),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal)
            when {
                window == null -> Text(
                    stringResource(R.string.outing_step_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )

                showRange && window.earliest != window.latest -> Text(
                    stringResource(
                        R.string.outing_window,
                        window.earliest.toLocalTime().format(timeFormatter),
                        window.latest.toLocalTime().format(timeFormatter),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/** What may call the outing off or shorten it: storm, the forecast, daylight left and the flow. */
@Composable
private fun Signals(state: OutingPlanUiState, timeFormatter: DateTimeFormatter) {
    val colors = LocalDcColors.current
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val day = state.dayWeather
        when {
            day?.condition == WeatherCondition.THUNDERSTORM -> {
                SignalRow(
                    Icons.Default.WarningAmber,
                    stringResource(R.string.outing_conditions_storm),
                    MaterialTheme.colorScheme.error,
                    bold = true,
                )
                SignalRow(weatherIcon(day.condition), forecastText(resources, day, locale), colors.textSecondary)
            }

            day != null -> SignalRow(weatherIcon(day.condition), forecastText(resources, day, locale), colors.textSecondary)

            else -> SignalRow(
                weatherIcon(WeatherCondition.UNKNOWN),
                stringResource(R.string.outing_conditions_no_forecast),
                colors.textMuted,
            )
        }
        DaylightSignals(state, timeFormatter)
        val flow = buildList {
            state.estimate?.let { estimate ->
                val label = estimate.likelyRange()?.let { debitRangeLabel(it) } ?: estimate.ordinalLevel?.let { debitLevelLabel(it) }
                label?.let { add(stringResource(R.string.outing_conditions_estimate, it)) }
            }
            state.lastReport?.let { report ->
                add(
                    stringResource(
                        R.string.outing_conditions_last_report,
                        debitLevelLabel(report.niveau),
                        relativeDayLabel(report.date, state.today),
                    ),
                )
            }
        }
        if (flow.isNotEmpty()) SignalRow(Icons.Default.WaterDrop, flow.joinToString("\n"), colors.textSecondary)
    }
}

@Composable
private fun SignalRow(icon: ImageVector, text: String, color: Color, modifier: Modifier = Modifier, bold: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = if (bold) color else LocalDcColors.current.textPrimary,
            modifier = modifier,
        )
    }
}

@Composable
private fun DaylightSignals(state: OutingPlanUiState, timeFormatter: DateTimeFormatter) {
    val colors = LocalDcColors.current
    val tag = Modifier.testTag(TestTags.OUTING_DAYLIGHT)
    when (val daylight = state.daylight) {
        is DaylightCheck.Ok -> {
            SignalRow(
                Icons.Default.WbTwilight,
                stringResource(R.string.outing_daylight_ok, formatDuration(daylight.margin), daylight.sunset.format(timeFormatter)),
                colors.textSecondary,
                modifier = tag,
            )
            daylight.latestDescentStart?.let {
                SignalRow(
                    Icons.Default.Flag,
                    stringResource(R.string.outing_daylight_turn_around, it.format(timeFormatter)),
                    colors.textSecondary,
                )
            }
        }

        is DaylightCheck.Late -> {
            val latest = daylight.latestAnchor
            val place = state.place?.takeIf { state.schedule?.anchor == OutingAnchor.LEAVE_PLACE }
            val text = when {
                latest == null -> stringResource(R.string.outing_daylight_too_long, daylight.sunset.format(timeFormatter))

                place != null -> stringResource(
                    R.string.outing_daylight_late_place,
                    daylight.sunset.format(timeFormatter),
                    place.name,
                    latest.format(timeFormatter),
                )

                else -> stringResource(R.string.outing_daylight_late, daylight.sunset.format(timeFormatter), latest.format(timeFormatter))
            }
            SignalRow(Icons.Default.WarningAmber, text, MaterialTheme.colorScheme.error, bold = true, modifier = tag)
        }

        DaylightCheck.Unknown -> SignalRow(
            Icons.Default.Info,
            stringResource(R.string.outing_daylight_unknown),
            colors.textMuted,
            modifier = tag,
        )
    }
}

/** How long after the latest return the relative worries. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AlertMarginDialog(state: OutingPlanUiState, onMarginSelected: (Int) -> Unit, onDismiss: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.outing_alert_margin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.outing_alert_margin_hint), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutingChoices.ALERT_MARGINS.forEach { hours ->
                        FilterChip(
                            selected = state.choices.alertMarginHours == hours,
                            onClick = { onMarginSelected(hours) },
                            label = { Text(stringResource(R.string.outing_alert_margin_value, hours)) },
                        )
                    }
                }
                state.alertAt?.let { alertAt ->
                    Text(
                        stringResource(R.string.outing_alert_time, timeOfOuting(alertAt, state.choices.date, locale)),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** A kept outing says first where it stands: draft, followed or under way, and what the relative was told. */
@Composable
internal fun FollowStatusCard(state: OutingPlanUiState, timeFormatter: DateTimeFormatter, onNotify: () -> Unit) {
    val saved = state.savedOuting ?: return
    val colors = LocalDcColors.current
    val locale = LocalLocale.current.platformLocale
    if (state.stage == OutingStage.DRAFT) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag(TestTags.OUTING_STATUS),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.outing_status_draft), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        return
    }
    val alert = state.alertAt?.let { timeOfOuting(it, state.choices.date, locale) }.orEmpty()
    DcCard(
        modifier = Modifier.fillMaxWidth().testTag(TestTags.OUTING_STATUS),
        variant = if (state.alertChangedSinceInformed) DcCardVariant.Warning else DcCardVariant.Condition,
        contentPadding = 14.dp,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                when {
                    state.alertChangedSinceInformed -> Icons.Default.WarningAmber
                    state.stage == OutingStage.UNDERWAY -> Icons.Default.Hiking
                    else -> Icons.Default.CheckCircle
                },
                contentDescription = null,
                tint = if (state.alertChangedSinceInformed) colors.warning else MaterialTheme.colorScheme.primary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (state.stage == OutingStage.UNDERWAY) {
                    val back = state.expectedBack?.toLocalTime()?.format(timeFormatter).orEmpty()
                    Text(
                        stringResource(R.string.outing_status_underway, back),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(stringResource(R.string.outing_alert_time, alert), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        stringResource(R.string.outing_status_tracked, alert),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val informedAt = saved.informedAlertAt
                when {
                    state.alertChangedSinceInformed && informedAt != null -> {
                        val told = timeOfOuting(
                            LocalDateTime.ofInstant(informedAt, java.time.ZoneId.systemDefault()),
                            state.choices.date,
                            locale,
                        )
                        Text(stringResource(R.string.outing_status_alert_changed, told, alert), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onNotify) { Text(stringResource(R.string.outing_alert_changed_action)) }
                    }

                    saved.relativeInformed -> Text(
                        stringResource(R.string.outing_status_relative_informed),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    else -> Text(
                        stringResource(R.string.outing_status_relative_not_informed),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

/** The memo written on the canyon page (parking, gear, traps), a few lines until touched. */
@Composable
internal fun MemoCard(memo: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    DcCard(modifier = Modifier.fillMaxWidth(), contentPadding = 14.dp, onClick = { expanded = !expanded }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.AutoMirrored.Filled.StickyNote2, contentDescription = null, tint = LocalDcColors.current.textSecondary)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.personal_note_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    memo,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else MEMO_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The one action that matters now, always at hand: turn the follow-up on, tell a relative, then
 * once under way say one is back or push the alert back; the emergency screen next to it.
 */
@Composable
internal fun PlanBottomBar(
    state: OutingPlanUiState,
    onActivate: () -> Unit,
    onNotify: () -> Unit,
    onEnd: () -> Unit,
    onDelay: (Int) -> Unit,
    onEmergency: () -> Unit,
) {
    val colors = LocalDcColors.current
    var delayMenu by remember { mutableStateOf(false) }
    Surface(color = colors.backgroundElevated, shadowElevation = 6.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.stage == OutingStage.NEW || state.stage == OutingStage.DRAFT) {
                Text(
                    stringResource(R.string.outing_activate_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.stage) {
                    OutingStage.NEW, OutingStage.DRAFT -> Button(
                        onClick = onActivate,
                        enabled = state.schedule != null,
                        modifier = Modifier.weight(1f).testTag(TestTags.OUTING_ACTIVATE_BUTTON),
                    ) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null)
                        Text(stringResource(R.string.outing_activate_action), modifier = Modifier.padding(start = 8.dp))
                    }

                    OutingStage.TRACKED -> {
                        val relative = state.savedOuting?.relativeInformed != true || state.alertChangedSinceInformed
                        Button(onClick = onNotify, modifier = Modifier.weight(1f).testTag(TestTags.OUTING_NOTIFY_BUTTON)) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                            Text(
                                stringResource(if (relative) R.string.outing_notify_relative else R.string.outing_notify_other),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }

                    OutingStage.UNDERWAY -> {
                        Button(onClick = onEnd, modifier = Modifier.weight(1f).testTag(TestTags.OUTING_END_BUTTON)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                            Text(
                                stringResource(R.string.outing_end_action),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Box {
                            OutlinedButton(onClick = { delayMenu = true }, modifier = Modifier.testTag(TestTags.OUTING_DELAY_BUTTON)) {
                                Text(stringResource(R.string.outing_delay_short), maxLines = 1)
                            }
                            DcDropdownMenu(expanded = delayMenu, onDismissRequest = { delayMenu = false }) {
                                DELAYS_MINUTES.forEach { minutes ->
                                    DcDropdownMenuItem(
                                        text = stringResource(R.string.outing_delay_by, formatMinutes(minutes.toLong())),
                                        onClick = {
                                            delayMenu = false
                                            onDelay(minutes)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                EmergencyIconButton(onClick = onEmergency)
            }
        }
    }
}

private val DELAYS_MINUTES = listOf(30, 60, 120)
private val TIME_COLUMN = 64.dp
private const val MEMO_LINES = 3

/** The page of OpenStreetMap where anyone can report a mistake of the map, which routing services ask to show. */
internal const val FIX_THE_MAP_URL = "https://www.openstreetmap.org/fixthemap"
