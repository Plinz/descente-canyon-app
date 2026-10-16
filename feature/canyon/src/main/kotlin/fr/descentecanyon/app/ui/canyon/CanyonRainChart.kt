package fr.descentecanyon.app.ui.canyon

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.HourlyPrecipitation
import fr.descentecanyon.app.domain.model.RainOutlook
import fr.descentecanyon.app.domain.model.WeatherCondition
import fr.descentecanyon.app.ui.design.LocalDcColors
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Rain hour by hour, from the last days to the coming ones, split at the current hour: when it
 * rained matters as much as how much. Thunderstorm hours are shaded and named in a legend.
 */
@Composable
internal fun RainTimelineChart(rain: RainOutlook, now: LocalDateTime, modifier: Modifier = Modifier) {
    val hours = rain.hours
    if (hours.isEmpty()) return
    val colors = LocalDcColors.current
    // Neutral grid, never in the hue of the bars.
    val grid = MaterialTheme.colorScheme.onSurfaceVariant
    val nowColor = MaterialTheme.colorScheme.onSurface
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val textMeasurer = rememberTextMeasurer()
    val today = now.toLocalDate()
    val scaleMm = rainScaleMm(hours.maxOf { it.precipitationMm })

    // Each bar is the rain of the hour before its time: it belongs to the day that hour starts in.
    val barDays = hours.map { it.dateTime.minusHours(1).toLocalDate() }
    val dayLabels = barDays.distinct().associateWith { forecastDayLabel(it, today) }
    val nowLabel = stringResource(R.string.weather_now)
    val scaleLabel = String.format(LocalLocale.current.platformLocale, "%.0f mm/h", scaleMm)
    val description = rainChartDescription(rain)
    val hasStorm = hours.any { it.isThunderstorm() }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT)
                .clearAndSetSemantics { contentDescription = description },
        ) {
            val plotTop = TOP_STRIP.toPx()
            val plotBottom = size.height - BOTTOM_STRIP.toPx()
            val plotHeight = plotBottom - plotTop
            val slot = size.width / hours.size
            val gap = min(1.dp.toPx(), slot * 0.3f)
            val barWidth = slot - gap
            val hairline = 1.dp.toPx()

            // Thunderstorm hours, behind the bars.
            hours.forEachIndexed { index, hour ->
                if (hour.isThunderstorm()) {
                    drawRect(
                        color = colors.warning.copy(alpha = 0.22f),
                        topLeft = Offset(index * slot, plotTop),
                        size = Size(slot, plotHeight),
                    )
                }
            }

            // Recessive grid: the scale line, the baseline and a line at each midnight.
            drawLine(grid.copy(alpha = 0.2f), Offset(0f, plotTop), Offset(size.width, plotTop), hairline)
            drawLine(grid.copy(alpha = 0.4f), Offset(0f, plotBottom), Offset(size.width, plotBottom), hairline)
            barDays.forEachIndexed { index, day ->
                if (index > 0 && day != barDays[index - 1]) {
                    val x = index * slot
                    drawLine(grid.copy(alpha = 0.2f), Offset(x, plotTop), Offset(x, plotBottom + 4.dp.toPx()), hairline)
                }
            }

            // One bar per hour, rounded at the data end, at least visible when it rained at all.
            val corner = min(barWidth / 2f, 2.dp.toPx())
            hours.forEachIndexed { index, hour ->
                if (hour.precipitationMm <= 0.0) return@forEachIndexed
                val height = max((hour.precipitationMm / scaleMm).toFloat().coerceAtMost(1f) * plotHeight, hairline)
                val left = index * slot + gap / 2f
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = left,
                            top = plotBottom - height,
                            right = left + barWidth,
                            bottom = plotBottom,
                            topLeftCornerRadius = CornerRadius(corner),
                            topRightCornerRadius = CornerRadius(corner),
                        ),
                    )
                }
                drawPath(path, colors.water)
            }

            // Day names, centered on the part of the day shown, when there is room for them.
            var start = 0
            barDays.forEachIndexed { index, day ->
                val isLast = index == barDays.lastIndex
                if (isLast || barDays[index + 1] != day) {
                    val layout = textMeasurer.measure(dayLabels.getValue(day), labelStyle)
                    val spanStart = start * slot
                    val spanWidth = (index + 1 - start) * slot
                    if (layout.size.width + 4.dp.toPx() <= spanWidth) {
                        drawText(layout, topLeft = Offset(spanStart + (spanWidth - layout.size.width) / 2f, plotBottom + 3.dp.toPx()))
                    }
                    start = index + 1
                }
            }

            // Now, between the past and the coming hours, and the value of the scale line.
            val nowX = rain.past.size * slot
            drawLine(nowColor.copy(alpha = 0.75f), Offset(nowX, plotTop - 2.dp.toPx()), Offset(nowX, plotBottom), 1.5.dp.toPx())
            val nowLayout = textMeasurer.measure(nowLabel, labelStyle)
            val nowLeft = (nowX - nowLayout.size.width / 2f).coerceIn(0f, max(0f, size.width - nowLayout.size.width))
            drawText(nowLayout, topLeft = Offset(nowLeft, 0f))
            drawScaleLabel(textMeasurer.measure(scaleLabel, labelStyle), nowLeft, nowLeft + nowLayout.size.width)
        }
        if (hasStorm) {
            ThunderstormHoursLegend()
        }
    }
}

/** At the right end of the scale line, or at its left end when "now" takes that place. */
private fun DrawScope.drawScaleLabel(layout: TextLayoutResult, nowLeft: Float, nowRight: Float) {
    val margin = 8.dp.toPx()
    val right = size.width - layout.size.width
    val x = when {
        right >= nowRight + margin -> right
        layout.size.width + margin <= nowLeft -> 0f
        else -> return
    }
    drawText(layout, topLeft = Offset(x, 0f))
}

@Composable
private fun ThunderstormHoursLegend() {
    val warning = LocalDcColors.current.warning
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(width = 8.dp, height = 12.dp),
            shape = RoundedCornerShape(2.dp),
            color = warning.copy(alpha = 0.22f),
            border = BorderStroke(1.dp, warning.copy(alpha = 0.6f)),
        ) {}
        Text(
            text = stringResource(R.string.weather_storm_hours_legend),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What the chart shows, for screen readers: the window and its heaviest hour. */
@Composable
private fun rainChartDescription(rain: RainOutlook): String {
    val heaviest = rain.hours.maxByOrNull { it.precipitationMm }
    if (heaviest == null || heaviest.precipitationMm <= 0.0) {
        return stringResource(R.string.weather_rain_chart_description_dry, rain.past.size, rain.next.size)
    }
    val locale = LocalLocale.current.platformLocale
    val hourStart = heaviest.dateTime.minusHours(1)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(hourStart)
    return stringResource(
        R.string.weather_rain_chart_description,
        rain.past.size,
        rain.next.size,
        String.format(locale, "%.1f mm/h", heaviest.precipitationMm),
        "${dayName(hourStart.toLocalDate())} $time",
    )
}

internal fun HourlyPrecipitation.isThunderstorm(): Boolean = WeatherCondition.fromWmoCode(weatherCode) == WeatherCondition.THUNDERSTORM

/** A round top for the scale, at least 1 mm/h so that a drizzle does not look like a downpour. */
internal fun rainScaleMm(heaviestMm: Double): Double = RAIN_SCALE_STEPS.firstOrNull { it >= heaviestMm }
    ?: (ceil(heaviestMm / RAIN_SCALE_STEPS.last()) * RAIN_SCALE_STEPS.last())

private val RAIN_SCALE_STEPS = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0)
private val TOP_STRIP = 16.dp
private val BOTTOM_STRIP = 18.dp
private val CHART_HEIGHT = 92.dp
