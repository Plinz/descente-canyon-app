package fr.descentecanyon.app.ui.outing

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.DriveTime
import fr.descentecanyon.app.domain.model.DriveTimeSource
import fr.descentecanyon.app.domain.model.GroupLevel
import fr.descentecanyon.app.domain.model.OutingAnchor
import fr.descentecanyon.app.domain.model.OutingSettings
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.model.WeatherCondition
import fr.descentecanyon.app.ui.common.openUrl
import fr.descentecanyon.app.ui.common.startActivitySafely
import fr.descentecanyon.app.ui.components.debitLevelLabel
import fr.descentecanyon.app.ui.components.debitRangeLabel
import fr.descentecanyon.app.ui.components.relativeDayLabel
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcCardVariant
import fr.descentecanyon.app.ui.design.DcDatePickerDialog
import fr.descentecanyon.app.ui.design.DcDropdownMenu
import fr.descentecanyon.app.ui.design.DcDropdownMenuItem
import fr.descentecanyon.app.ui.design.DcSelectField
import fr.descentecanyon.app.ui.design.DcTimePickerDialog
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.test.TestTags
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The memo written on the canyon page: parking, gear and traps are what one checks before leaving. */
@Composable
internal fun MemoCard(memo: String) {
    DcCard(modifier = Modifier.fillMaxWidth(), contentPadding = 14.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.AutoMirrored.Filled.StickyNote2, contentDescription = null, tint = LocalDcColors.current.textSecondary)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.personal_note_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(memo, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** A saved outing says so first: what the relative was told, and whether the plan on screen differs. */
@Composable
internal fun OutingStatusBanner(state: OutingPlanUiState) {
    val saved = state.savedOuting ?: return
    val locale = LocalLocale.current.platformLocale
    val alert = saved.alertAt.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
    DcCard(
        modifier = Modifier.fillMaxWidth().testTag(TestTags.OUTING_STATUS),
        variant = if (state.hasUnsavedChanges) DcCardVariant.Warning else DcCardVariant.Condition,
        contentPadding = 14.dp,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (state.hasUnsavedChanges) Icons.Default.WarningAmber else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (state.hasUnsavedChanges) LocalDcColors.current.warning else MaterialTheme.colorScheme.primary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.outing_status_saved, timeOfOuting(alert, state.choices.date, locale)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (saved.relativeInformed) {
                    Text(stringResource(R.string.outing_status_relative_informed), style = MaterialTheme.typography.bodySmall)
                }
                if (state.hasUnsavedChanges) {
                    Text(stringResource(R.string.outing_status_unsaved), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WhenCard(
    state: OutingPlanUiState,
    onDateSelected: (LocalDate) -> Unit,
    onTimeSelected: (LocalTime) -> Unit,
    onAnchorSelected: (OutingAnchor) -> Unit,
    onPlaceSelected: (Long?) -> Unit,
    onManagePlaces: () -> Unit,
    onSetManualDrive: (Int?) -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val date = state.choices.date
    val tomorrow = state.today.plusDays(1)
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val place = state.place
    val anchor = state.schedule?.anchor ?: state.choices.anchor
    val timeLabel = if (anchor == OutingAnchor.LEAVE_PLACE && place != null) {
        stringResource(R.string.outing_anchor_leave_place, place.name)
    } else {
        stringResource(R.string.outing_anchor_arrive_parking)
    }
    if (showDatePicker) {
        DcDatePickerDialog(initialDate = date, onDateSelected = onDateSelected, onDismiss = {
            showDatePicker = false
        }, minDate = state.today)
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
    val dateLabel = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date)
    DcCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.outing_date_label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = date == state.today, onClick = { onDateSelected(state.today) }, label = {
                    Text(stringResource(R.string.outing_day_today))
                })
                FilterChip(selected = date == tomorrow, onClick = { onDateSelected(tomorrow) }, label = {
                    Text(stringResource(R.string.outing_day_tomorrow))
                })
                FilterChip(
                    selected = date != state.today && date != tomorrow,
                    onClick = { showDatePicker = true },
                    label = { Text(stringResource(R.string.outing_day_other)) },
                )
            }
            Text(dateLabel.replaceFirstChar { it.titlecase(locale) }, style = MaterialTheme.typography.bodyMedium)

            Text(stringResource(R.string.outing_place_label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            PlaceSelector(places = state.places, selected = place, onPlaceSelected = onPlaceSelected, onManagePlaces = onManagePlaces)
            if (place != null) {
                DriveLine(state = state, onSetManualDrive = onSetManualDrive)
            }

            Text(stringResource(R.string.outing_time_label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (place != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.choices.anchor == OutingAnchor.LEAVE_PLACE,
                        onClick = { onAnchorSelected(OutingAnchor.LEAVE_PLACE) },
                        label = { Text(stringResource(R.string.outing_anchor_leave_place, place.name)) },
                    )
                    FilterChip(
                        selected = state.choices.anchor == OutingAnchor.ARRIVE_PARKING,
                        onClick = { onAnchorSelected(OutingAnchor.ARRIVE_PARKING) },
                        label = { Text(stringResource(R.string.outing_anchor_arrive_parking)) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.testTag(TestTags.OUTING_START_TIME)) {
                    Icon(Icons.Default.AccessTime, contentDescription = stringResource(R.string.outing_change_time))
                    Text(state.choices.anchorTime.format(rememberTimeFormatter()), modifier = Modifier.padding(start = 8.dp))
                }
                Text(timeLabel, style = MaterialTheme.typography.bodyMedium, color = LocalDcColors.current.textSecondary)
            }
        }
    }
}

@Composable
private fun PlaceSelector(places: List<Place>, selected: Place?, onPlaceSelected: (Long?) -> Unit, onManagePlaces: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        DcSelectField(
            label = stringResource(R.string.outing_place_field),
            value = selected?.name ?: stringResource(R.string.outing_place_none),
            onClick = { expanded = true },
            icon = Icons.Default.Place,
            isActive = selected != null,
            onClear = { onPlaceSelected(null) },
            modifier = Modifier.testTag(TestTags.OUTING_PLACE_FIELD),
        )
        DcDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DcDropdownMenuItem(
                text = stringResource(R.string.outing_place_none),
                isSelected = selected == null,
                onClick = {
                    expanded = false
                    onPlaceSelected(null)
                },
            )
            places.forEach { place ->
                DcDropdownMenuItem(
                    text = place.name,
                    supportingText = stayLabel(place),
                    isSelected = place.id == selected?.id,
                    onClick = {
                        expanded = false
                        onPlaceSelected(place.id)
                    },
                )
            }
            HorizontalDivider()
            DcDropdownMenuItem(
                text = stringResource(if (places.isEmpty()) R.string.places_add else R.string.places_manage),
                icon = if (places.isEmpty()) Icons.Default.Add else Icons.Default.Edit,
                onClick = {
                    expanded = false
                    onManagePlaces()
                },
            )
        }
    }
    if (places.isEmpty()) {
        Text(
            stringResource(R.string.outing_place_hint),
            style = MaterialTheme.typography.bodySmall,
            color = LocalDcColors.current.textSecondary,
        )
    }
}

/** "12 – 18 July", the days of a stay at the place. */
@Composable
internal fun stayLabel(place: Place): String? {
    val start = place.stayStart ?: return null
    val end = place.stayEnd ?: return null
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalLocale.current.platformLocale)
    return stringResource(R.string.places_stay_range, start.format(formatter), end.format(formatter))
}

/** The drive to the parking, where its time comes from, the user's own time and the way there in a navigation app. */
@Composable
private fun DriveLine(state: OutingPlanUiState, onSetManualDrive: (Int?) -> Unit) {
    val context = LocalContext.current
    val colors = LocalDcColors.current
    var editing by remember { mutableStateOf(false) }
    val drive = state.drive
    if (editing) {
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
            if (state.meetingParking != null) {
                IconButton(onClick = { editing = true }) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.outing_drive_edit), tint = colors.textSecondary)
                }
                IconButton(onClick = {
                    val point = state.meetingParking
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GroupCard(
    state: OutingPlanUiState,
    onSizeSelected: (Int) -> Unit,
    onLevelSelected: (GroupLevel) -> Unit,
    onShuttleSelected: (Boolean) -> Unit,
) {
    val size = state.choices.groupSize
    DcCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.outing_group_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
}

@Composable
internal fun groupLevelLabel(level: GroupLevel): String = stringResource(
    when (level) {
        GroupLevel.AUTONOMOUS -> R.string.outing_level_autonomous
        GroupLevel.WITH_BEGINNERS -> R.string.outing_level_beginners
        GroupLevel.DISCOVERY -> R.string.outing_level_discovery
    },
)

@Composable
internal fun ConditionsCard(state: OutingPlanUiState) {
    val colors = LocalDcColors.current
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    DcCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.outing_conditions_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val day = state.dayWeather
            if (day?.condition == WeatherCondition.THUNDERSTORM) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.WarningAmber, contentDescription = null, tint = colors.warning, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.outing_conditions_storm),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (day != null) {
                Text(forecastText(resources, day, locale), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    stringResource(R.string.outing_conditions_no_forecast),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            state.estimate?.let { estimate ->
                val label = estimate.likelyRange()?.let { debitRangeLabel(it) } ?: estimate.ordinalLevel?.let { debitLevelLabel(it) }
                label?.let { Text(stringResource(R.string.outing_conditions_estimate, it), style = MaterialTheme.typography.bodySmall) }
            }
            state.lastReport?.let { report ->
                Text(
                    stringResource(
                        R.string.outing_conditions_last_report,
                        debitLevelLabel(report.niveau),
                        relativeDayLabel(report.date, state.today),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Saving, then following the outing: back, alert pushed back, calendar, cancellation. */
@Composable
internal fun OutingStatusCard(
    state: OutingPlanUiState,
    onSave: () -> Unit,
    onEnd: () -> Unit,
    onDelay: (Int) -> Unit,
    onAddToCalendar: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalDcColors.current
    var delayMenu by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            text = { Text(stringResource(R.string.outing_cancel_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    onCancel()
                }) { Text(stringResource(R.string.outing_cancel_action)) }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    DcCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.outing_follow_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val saved = state.savedOuting
            if (saved == null) {
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth().testTag(TestTags.OUTING_SAVE_BUTTON)) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null)
                    Text(stringResource(R.string.outing_save_action), modifier = Modifier.padding(start = 8.dp))
                }
                Text(stringResource(R.string.outing_save_hint), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            } else {
                if (state.hasUnsavedChanges) {
                    Button(onClick = onSave, modifier = Modifier.fillMaxWidth().testTag(TestTags.OUTING_UPDATE_BUTTON)) {
                        Text(stringResource(R.string.outing_update_action))
                    }
                }
                // Stacked: side by side, the labels wrap on a phone.
                Button(onClick = onEnd, modifier = Modifier.fillMaxWidth().testTag(TestTags.OUTING_END_BUTTON)) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Text(stringResource(R.string.outing_end_action), modifier = Modifier.padding(start = 8.dp))
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        delayMenu = true
                    }, modifier = Modifier.fillMaxWidth().testTag(TestTags.OUTING_DELAY_BUTTON)) {
                        Icon(Icons.Default.Snooze, contentDescription = null)
                        Text(stringResource(R.string.outing_delay_action), modifier = Modifier.padding(start = 8.dp))
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
                Text(stringResource(R.string.outing_delay_hint), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAddToCalendar) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null)
                    Text(stringResource(R.string.outing_calendar_action), modifier = Modifier.padding(start = 8.dp))
                }
                if (saved != null) {
                    TextButton(onClick = { confirmCancel = true }) { Text(stringResource(R.string.outing_cancel_action)) }
                }
            }
        }
    }
}

private val DELAYS_MINUTES = listOf(30, 60, 120)

/** The page of OpenStreetMap where anyone can report a mistake of the map, which routing services ask to show. */
internal const val FIX_THE_MAP_URL = "https://www.openstreetmap.org/fixthemap"
