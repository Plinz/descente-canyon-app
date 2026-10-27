package fr.descentecanyon.app.ui.design

import android.text.format.DateFormat
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.ui.test.TestTags
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The calendar of the app (the platform dialog ignored its colors and stayed light in dark mode).
 * Days outside [minDate]..[maxDate] cannot be picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DcDatePickerDialog(
    initialDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    minDate: LocalDate? = null,
    maxDate: LocalDate? = null,
) {
    val colors = LocalDcColors.current
    val selectableDates = remember(minDate, maxDate) { DateRange(minDate, maxDate) }
    // The picker counts in UTC milliseconds: a date is its UTC midnight.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = (minDate?.year ?: DatePickerDefaults.YearRange.first)..(maxDate?.year ?: DatePickerDefaults.YearRange.last),
        selectableDates = selectableDates,
    )
    val pickerColors = DatePickerDefaults.colors(containerColor = colors.backgroundElevated)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onDateSelected(it.toUtcDate()) }
                    onDismiss()
                },
                enabled = state.selectedDateMillis != null,
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        colors = pickerColors,
    ) {
        DatePicker(state = state, colors = pickerColors)
    }
}

/**
 * The calendar of the app for a span of days, such as a stay: a first tap picks the first day, a
 * second one the last day. A single day picked gives a span of one day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DcDateRangePickerDialog(
    initialStart: LocalDate,
    initialEnd: LocalDate,
    onRangeSelected: (start: LocalDate, end: LocalDate) -> Unit,
    onDismiss: () -> Unit,
    minDate: LocalDate? = null,
) {
    val colors = LocalDcColors.current
    val selectableDates = remember(minDate) { DateRange(minDate, null) }
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStart.toUtcMillis(),
        initialSelectedEndDateMillis = initialEnd.toUtcMillis(),
        initialDisplayedMonthMillis = initialStart.toUtcMillis(),
        yearRange = (minDate?.year ?: DatePickerDefaults.YearRange.first)..DatePickerDefaults.YearRange.last,
        selectableDates = selectableDates,
    )
    val pickerColors = DatePickerDefaults.colors(containerColor = colors.backgroundElevated)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedStartDateMillis?.let { start ->
                        onRangeSelected(start.toUtcDate(), (state.selectedEndDateMillis ?: start).toUtcDate())
                    }
                    onDismiss()
                },
                enabled = state.selectedStartDateMillis != null,
                modifier = Modifier.testTag(TestTags.DATE_RANGE_CONFIRM),
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        colors = pickerColors,
    ) {
        // The months scroll inside the dialog: the picker takes the height left by the buttons.
        DateRangePicker(
            state = state,
            colors = pickerColors,
            // Day and month only: with the years, the two dates did not fit on one line.
            headline = {
                val locale = LocalLocale.current.platformLocale
                val formatter = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMM"), locale) }
                fun Long?.label() = this?.toUtcDate()?.format(formatter) ?: "–"
                Text(
                    "${state.selectedStartDateMillis.label()} – ${state.selectedEndDateMillis.label()}",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 64.dp, end = 12.dp),
                )
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/** The clock of the app, in 24-hour or AM/PM form as the phone is set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DcTimePickerDialog(
    title: String,
    initialTime: LocalTime,
    is24Hour: Boolean,
    onTimeSelected: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDcColors.current
    val state = rememberTimePickerState(initialHour = initialTime.hour, initialMinute = initialTime.minute, is24Hour = is24Hour)
    // Material 1.4 paints this dialog with `surface` tinted for its elevation, whatever containerColor
    // says: both are set to the dialog color so it matches the calendar and the other dialogs.
    val dialogScheme = MaterialTheme.colorScheme.copy(surface = colors.backgroundElevated, surfaceTint = colors.backgroundElevated)
    MaterialTheme(colorScheme = dialogScheme) {
        TimePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    onTimeSelected(LocalTime.of(state.hour, state.minute))
                    onDismiss()
                }) { Text(stringResource(R.string.ok)) }
            },
            title = { Text(title) },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
            containerColor = colors.backgroundElevated,
        ) {
            TimePicker(state = state, colors = TimePickerDefaults.colors(clockDialColor = colors.surfaceRaised))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private class DateRange(private val min: LocalDate?, private val max: LocalDate?) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
        val date = utcTimeMillis.toUtcDate()
        return (min == null || !date.isBefore(min)) && (max == null || !date.isAfter(max))
    }

    override fun isSelectableYear(year: Int): Boolean = (min == null || year >= min.year) && (max == null || year <= max.year)
}

private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
