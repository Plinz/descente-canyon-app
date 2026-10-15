package fr.descentecanyon.app.ui.outing

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonDuration
import fr.descentecanyon.app.domain.model.DaylightCheck
import fr.descentecanyon.app.domain.model.TimeWindow
import fr.descentecanyon.app.domain.model.WeatherCondition
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.components.debitLevelLabel
import fr.descentecanyon.app.ui.components.debitRangeLabel
import fr.descentecanyon.app.ui.components.relativeDayLabel
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.test.TestTags
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun OutingPlanScreen(
    onBackClick: () -> Unit,
    onOpenEmergency: (Int) -> Unit,
    onReportFlow: (Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OutingPlanViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val timeFormatter = rememberTimeFormatter()
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)

    Scaffold(
        modifier = modifier,
        topBar = {
            CompactAppBar(
                title = state.canyon?.canyon?.nom ?: stringResource(R.string.outing_plan_title),
                navigation = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.fillMaxHeight().width(contentWidth).testTag(TestTags.OUTING_PLAN_LIST),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { WhenCard(state = state, onDateSelected = viewModel::selectDate, onTimeSelected = viewModel::selectStartTime) }
                item { TimelineCard(state = state, timeFormatter = timeFormatter) }
                item { ConditionsCard(state = state) }
                item {
                    RelativeCard(
                        state = state,
                        timeFormatter = timeFormatter,
                        onMarginSelected = viewModel::selectAlertMargin,
                        onSave = {
                            viewModel.saveOuting()
                            val back = state.expectedBack?.toLocalTime()?.format(timeFormatter).orEmpty()
                            scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.outing_saved, back)) }
                        },
                        onEnd = {
                            viewModel.markBack()
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = resources.getString(R.string.outing_back_report_prompt),
                                    actionLabel = resources.getString(R.string.outing_report_action),
                                )
                                if (result == SnackbarResult.ActionPerformed) state.canyon?.canyon?.id?.let(onReportFlow)
                            }
                        },
                        onCancel = viewModel::cancelOuting,
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { state.canyon?.canyon?.id?.let(onOpenEmergency) },
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.OUTING_EMERGENCY_BUTTON),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Default.Emergency, contentDescription = null)
                        Text(stringResource(R.string.outing_emergency_action), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun WhenCard(state: OutingPlanUiState, onDateSelected: (LocalDate) -> Unit, onTimeSelected: (LocalTime) -> Unit) {
    val context = LocalContext.current
    val date = state.choices.date
    val tomorrow = state.today.plusDays(1)
    val dateLabel = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(LocalLocale.current.platformLocale).format(date)
    DcCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.outing_date_label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = date == state.today, onClick = {
                    onDateSelected(state.today)
                }, label = { Text(stringResource(R.string.outing_day_today)) })
                FilterChip(selected = date == tomorrow, onClick = {
                    onDateSelected(tomorrow)
                }, label = { Text(stringResource(R.string.outing_day_tomorrow)) })
                FilterChip(
                    selected = date != state.today && date != tomorrow,
                    onClick = {
                        DatePickerDialog(
                            context,
                            { _, year, month, day -> onDateSelected(LocalDate.of(year, month + 1, day)) },
                            date.year,
                            date.monthValue - 1,
                            date.dayOfMonth,
                        )
                            .apply { datePicker.minDate = System.currentTimeMillis() - 1_000 }
                            .show()
                    },
                    label = { Text(stringResource(R.string.outing_day_other)) },
                )
            }
            Text(
                dateLabel.replaceFirstChar {
                    it.titlecase(LocalLocale.current.platformLocale)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(R.string.outing_start_label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            OutlinedButton(
                onClick = {
                    TimePickerDialog(
                        context,
                        { _, hour, minute -> onTimeSelected(LocalTime.of(hour, minute)) },
                        state.choices.startTime.hour,
                        state.choices.startTime.minute,
                        DateFormat.is24HourFormat(context),
                    ).show()
                },
                modifier = Modifier.testTag(TestTags.OUTING_START_TIME),
            ) {
                Icon(Icons.Default.AccessTime, contentDescription = stringResource(R.string.outing_change_time))
                Text(state.choices.startTime.format(rememberTimeFormatter()), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun TimelineCard(state: OutingPlanUiState, timeFormatter: DateTimeFormatter) {
    val timeline = state.timeline ?: return
    val colors = LocalDcColors.current
    DcCard(modifier = Modifier.fillMaxWidth().testTag(TestTags.OUTING_TIMELINE)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.outing_timeline_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            TimelineRow(stringResource(R.string.outing_step_start), timeline.start.toLocalTime().format(timeFormatter))
            TimelineRow(stringResource(R.string.outing_step_descent_start), timeline.descentStart.label(timeFormatter))
            TimelineRow(stringResource(R.string.outing_step_descent_end), timeline.descentEnd.label(timeFormatter))
            TimelineRow(stringResource(R.string.outing_step_back), timeline.backAtCar.label(timeFormatter), emphasized = true)
            Text(
                text = stringResource(
                    R.string.outing_durations_source,
                    durationLabel(state.approach),
                    durationLabel(state.descent),
                    durationLabel(state.back),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
            DaylightLine(state.daylight, timeFormatter)
        }
    }
}

@Composable
private fun TimelineRow(label: String, value: String, emphasized: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (emphasized) FontWeight.Bold else FontWeight.SemiBold)
    }
}

@Composable
private fun TimeWindow?.label(timeFormatter: DateTimeFormatter): String {
    this ?: return stringResource(R.string.outing_step_unknown)
    val earliest = earliest.toLocalTime().format(timeFormatter)
    val latest = latest.toLocalTime().format(timeFormatter)
    return if (earliest == latest) earliest else stringResource(R.string.outing_time_range, earliest, latest)
}

@Composable
private fun DaylightLine(daylight: DaylightCheck, timeFormatter: DateTimeFormatter) {
    val colors = LocalDcColors.current
    val (text, color) = when (daylight) {
        is DaylightCheck.Ok -> stringResource(
            R.string.outing_daylight_ok,
            daylight.sunset.minus(daylight.margin).format(timeFormatter),
            formatDuration(daylight.margin),
            daylight.sunset.format(timeFormatter),
        ) to colors.textSecondary

        is DaylightCheck.Late -> {
            val latestStart = daylight.latestStart
            (
                if (latestStart != null) {
                    stringResource(R.string.outing_daylight_late, daylight.sunset.format(timeFormatter), latestStart.format(timeFormatter))
                } else {
                    stringResource(R.string.outing_daylight_too_long, daylight.sunset.format(timeFormatter))
                }
                ) to MaterialTheme.colorScheme.error
        }

        DaylightCheck.Unknown -> stringResource(R.string.outing_daylight_unknown) to colors.textMuted
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (daylight is DaylightCheck.Late) Icons.Default.WarningAmber else Icons.Default.WbTwilight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = color,
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.testTag(TestTags.OUTING_DAYLIGHT))
    }
}

@Composable
private fun ConditionsCard(state: OutingPlanUiState) {
    val colors = LocalDcColors.current
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
                Text(dayForecastText(day), style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun dayForecastText(day: fr.descentecanyon.app.domain.model.DailyWeather): String {
    val locale = LocalLocale.current.platformLocale
    val rain = day.precipitationSumMm?.let { String.format(locale, "%.1f mm", it) } ?: "--"
    val minimum = day.temperatureMinC?.let { "${it.roundToInt()}°" } ?: "--"
    val maximum = day.temperatureMaxC?.let { "${it.roundToInt()}°" } ?: "--"
    return stringResource(R.string.outing_conditions_forecast, weatherConditionText(day.condition), rain, minimum, maximum)
        .replaceFirstChar { it.titlecase(locale) }
}

@Composable
private fun weatherConditionText(condition: WeatherCondition): String = stringResource(
    when (condition) {
        WeatherCondition.CLEAR -> R.string.weather_condition_clear
        WeatherCondition.PARTLY_CLOUDY -> R.string.weather_condition_partly_cloudy
        WeatherCondition.CLOUDY -> R.string.weather_condition_cloudy
        WeatherCondition.FOG -> R.string.weather_condition_fog
        WeatherCondition.DRIZZLE -> R.string.weather_condition_drizzle
        WeatherCondition.RAIN -> R.string.weather_condition_rain
        WeatherCondition.SNOW -> R.string.weather_condition_snow
        WeatherCondition.THUNDERSTORM -> R.string.weather_condition_thunderstorm
        WeatherCondition.UNKNOWN -> R.string.weather_condition_unknown
    },
)

@Composable
private fun RelativeCard(
    state: OutingPlanUiState,
    timeFormatter: DateTimeFormatter,
    onMarginSelected: (Int) -> Unit,
    onSave: () -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalDcColors.current
    val shareTitle = stringResource(R.string.outing_message_share_title)
    val message = outingMessage(state, timeFormatter)
    DcCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.outing_alert_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.outing_alert_body), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            Text(stringResource(R.string.outing_alert_margin), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    stringResource(R.string.outing_alert_time, alertAt.toLocalTime().format(timeFormatter)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
                    context.startActivity(Intent.createChooser(intent, shareTitle))
                },
                modifier = Modifier.fillMaxWidth().testTag(TestTags.OUTING_SHARE_BUTTON),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Text(stringResource(R.string.outing_share_action), modifier = Modifier.padding(start = 8.dp))
            }
            val saved = state.savedOuting
            if (saved == null) {
                OutlinedButton(onClick = onSave, modifier = Modifier.fillMaxWidth().testTag(TestTags.OUTING_SAVE_BUTTON)) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null)
                    Text(stringResource(R.string.outing_save_action), modifier = Modifier.padding(start = 8.dp))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onEnd, modifier = Modifier.weight(1f).testTag(TestTags.OUTING_END_BUTTON)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Text(stringResource(R.string.outing_end_action), modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.outing_cancel_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun outingMessage(state: OutingPlanUiState, timeFormatter: DateTimeFormatter): String {
    val canyon = state.canyon?.canyon ?: return ""
    val locale = LocalLocale.current.platformLocale
    val resources = LocalResources.current
    val texts = OutingMessageTexts(
        header = { name, cotation -> resources.getString(R.string.outing_message_header, name, cotation) },
        place = { value -> resources.getString(R.string.outing_message_place, value) },
        whenWithReturn = { day, start, back -> resources.getString(R.string.outing_message_when, day, start, back) },
        whenWithoutReturn = { day, start -> resources.getString(R.string.outing_message_when_unknown, day, start) },
        alert = { time, number -> resources.getString(R.string.outing_message_alert, time, number) },
        downstreamParking = { coordinates -> resources.getString(R.string.outing_message_parking_downstream, coordinates) },
        upstreamParking = { coordinates -> resources.getString(R.string.outing_message_parking_upstream, coordinates) },
        sheet = { url -> resources.getString(R.string.outing_message_sheet, url) },
    )
    val day = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(state.choices.date)
        .replaceFirstChar { it.titlecase(locale) }
    val place = listOfNotNull(canyon.commune.takeIf { it.isNotBlank() }, canyon.departement, canyon.pays.takeIf { it.isNotBlank() })
        .joinToString(", ")
    return buildOutingMessage(
        texts = texts,
        data = OutingMessageData(
            canyonName = canyon.nom,
            cotation = canyon.cotation,
            place = place,
            day = day,
            start = state.choices.startTime.format(timeFormatter),
            back = state.timeline?.backAtCar?.latest?.toLocalTime()?.format(timeFormatter),
            alertTime = state.alertAt?.let { formatDateTimeShort(it, state.choices.date, timeFormatter, locale) }.orEmpty(),
            emergencyNumber = state.emergencyNumbers.first().number,
            downstreamParking = state.downstreamParking,
            upstreamParking = state.upstreamParking,
            sheetUrl = canyon.url,
        ),
    )
}

/** The alert time, with its date when it falls the next day. */
private fun formatDateTimeShort(dateTime: LocalDateTime, day: LocalDate, timeFormatter: DateTimeFormatter, locale: Locale): String =
    if (dateTime.toLocalDate() == day) {
        dateTime.toLocalTime().format(timeFormatter)
    } else {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).format(dateTime)
    }

@Composable
private fun durationLabel(duration: CanyonDuration): String = when (duration) {
    CanyonDuration.None -> stringResource(R.string.duration_none)

    CanyonDuration.Unknown -> stringResource(R.string.duration_unknown)

    is CanyonDuration.Known -> {
        val range = duration.range
        if (range.minMinutes == range.maxMinutes) {
            formatDuration(Duration.ofMinutes(range.minMinutes.toLong()))
        } else {
            stringResource(
                R.string.outing_time_range,
                formatDuration(Duration.ofMinutes(range.minMinutes.toLong())),
                formatDuration(Duration.ofMinutes(range.maxMinutes.toLong())),
            )
        }
    }
}

/** "45 min", "2 h", "1 h 30". */
internal fun formatDuration(duration: Duration): String {
    val hours = duration.toHours()
    val minutes = (duration.toMinutes() % 60).toInt()
    return when {
        hours == 0L -> "$minutes min"
        minutes == 0 -> "$hours h"
        else -> String.format(Locale.ROOT, "%d h %02d", hours, minutes)
    }
}

@Composable
private fun rememberTimeFormatter(): DateTimeFormatter {
    val locale = LocalLocale.current.platformLocale
    return remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale) }
}
