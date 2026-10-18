package fr.descentecanyon.app.ui.canyon

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.DailyWeather
import fr.descentecanyon.app.domain.model.WeatherCondition
import fr.descentecanyon.app.ui.design.LocalDcColors
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Today and the next days at the weather point: condition, rain and temperatures. */
@Composable
internal fun DailyForecastRow(daily: List<DailyWeather>, today: LocalDate, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DAY_TILE_SPACING),
    ) {
        daily.take(MAX_FORECAST_DAYS).forEach { day ->
            DailyForecastTile(day = day, today = today, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DailyForecastTile(day: DailyWeather, today: LocalDate, modifier: Modifier = Modifier) {
    val colors = LocalDcColors.current
    val isStorm = day.condition == WeatherCondition.THUNDERSTORM
    val isHeavyRain = (day.precipitationSumMm ?: 0.0) >= CanyonWeather.HEAVY_RAIN_DAY_MM
    val dayLabel = forecastDayLabel(day.date, today)
    val conditionLabel = weatherConditionLabel(day.condition)
    val rain = day.precipitationSumMm?.let(::formatRainMm) ?: "--"
    val minimum = day.temperatureMinC?.let(::formatTemperature) ?: "--"
    val maximum = day.temperatureMaxC?.let(::formatTemperature) ?: "--"
    val description = stringResource(R.string.weather_day_description, dayLabel, conditionLabel, rain, minimum, maximum)
    WeatherTile(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        highlighted = isStorm || isHeavyRain,
        horizontalPadding = 2.dp,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = dayLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Icon(
            imageVector = weatherConditionIcon(day.condition),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (isStorm) colors.warning else colors.water,
        )
        Text(
            text = rain,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isHeavyRain) FontWeight.Bold else null,
            color = if (isHeavyRain) colors.warning else Color.Unspecified,
            maxLines = 1,
        )
        Text(
            text = "$maximum/$minimum",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The one tile of the weather card, for the rain totals and the days alike. A highlighted tile
 * flags a thunderstorm; its label says so, never the color alone.
 */
@Composable
internal fun WeatherTile(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    horizontalPadding: Dp = 10.dp,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val warning = LocalDcColors.current.warning
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (highlighted) warning.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        border = if (highlighted) BorderStroke(1.dp, warning.copy(alpha = 0.6f)) else null,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 8.dp),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

/**
 * Thunderstorms are the main cause of sudden floods in canyons: the first stormy day of the
 * forecast is announced above everything else.
 */
@Composable
internal fun StormWarning(weather: CanyonWeather, now: LocalDateTime, modifier: Modifier = Modifier) {
    val storm = weather.nextThunderstorm(now) ?: return
    val colors = LocalDcColors.current
    val today = now.toLocalDate()
    val stormTime = storm.time
    val text = when {
        storm.date == today && stormTime != null -> stringResource(
            R.string.weather_storm_today_at,
            stormTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(LocalLocale.current.platformLocale)),
        )

        storm.date == today -> stringResource(R.string.weather_storm_today)

        storm.date == today.plusDays(1) -> stringResource(R.string.weather_storm_tomorrow)

        else -> stringResource(R.string.weather_storm_later, dayName(storm.date))
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.warning.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, colors.warning.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Default.WarningAmber, contentDescription = null, tint = colors.warning)
            Text(text = text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * A day of heavy rain ahead (and no storm already announced for it): 60 mm tomorrow must not look
 * like the dry days around it.
 */
@Composable
internal fun HeavyRainWarning(weather: CanyonWeather, now: LocalDateTime, modifier: Modifier = Modifier) {
    val today = now.toLocalDate()
    val day = weather.firstHeavyRainDay(from = today) ?: return
    if (weather.nextThunderstorm(now)?.date == day.date) return
    val amount = formatRainMm(day.precipitationSumMm ?: 0.0)
    val text = when (day.date) {
        today -> stringResource(R.string.weather_heavy_rain_today, amount)
        today.plusDays(1) -> stringResource(R.string.weather_heavy_rain_tomorrow, amount)
        else -> stringResource(R.string.weather_heavy_rain_later, dayName(day.date), amount)
    }
    val colors = LocalDcColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.warning.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, colors.warning.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Default.WarningAmber, contentDescription = null, tint = colors.warning)
            Text(text = text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Sunrise and sunset of the day, to fit the outing in the daylight. */
@Composable
internal fun SunTimesLine(day: DailyWeather, modifier: Modifier = Modifier) {
    val sunrise = day.sunrise ?: return
    val sunset = day.sunset ?: return
    val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(LocalLocale.current.platformLocale)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.WbTwilight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.weather_sun_times, sunrise.format(timeFormatter), sunset.format(timeFormatter)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shown when the network is unavailable and the last downloaded forecast is displayed. */
@Composable
internal fun CachedForecastLine(weather: CanyonWeather, isRefreshing: Boolean, modifier: Modifier = Modifier) {
    val locale = LocalLocale.current.platformLocale
    val downloadedAt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(locale)
        .format(weather.fetchedAt.atZone(ZoneId.systemDefault()))
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = LocalDcColors.current.offline,
            )
        }
        Text(
            text = stringResource(if (isRefreshing) R.string.weather_refreshing_cached else R.string.weather_offline_cached, downloadedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun forecastDayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today.minusDays(1) -> stringResource(R.string.weather_day_yesterday)
    today -> stringResource(R.string.weather_day_today)
    today.plusDays(1) -> stringResource(R.string.weather_day_tomorrow)
    else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, LocalLocale.current.platformLocale)
}

@Composable
internal fun dayName(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.FULL, LocalLocale.current.platformLocale)

@Composable
internal fun weatherConditionLabel(condition: WeatherCondition): String = stringResource(
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

private fun weatherConditionIcon(condition: WeatherCondition): ImageVector = when (condition) {
    WeatherCondition.CLEAR -> Icons.Default.WbSunny
    WeatherCondition.PARTLY_CLOUDY -> Icons.Default.WbCloudy
    WeatherCondition.CLOUDY, WeatherCondition.FOG -> Icons.Default.Cloud
    WeatherCondition.DRIZZLE -> Icons.Default.Grain
    WeatherCondition.RAIN -> Icons.Default.Umbrella
    WeatherCondition.SNOW -> Icons.Default.AcUnit
    WeatherCondition.THUNDERSTORM -> Icons.Default.Thunderstorm
    WeatherCondition.UNKNOWN -> Icons.Default.QuestionMark
}

/** Millimeters of rain: none, tenths below 10 mm, whole millimeters above. */
internal fun formatRainMm(value: Double): String = when {
    value <= 0.0 -> "0 mm"
    value < 10.0 -> String.format(Locale.getDefault(), "%.1f mm", value)
    else -> String.format(Locale.getDefault(), "%.0f mm", value)
}

private fun formatTemperature(value: Double): String = "${value.roundToInt()}°"

private const val MAX_FORECAST_DAYS = 6

internal val TILE_SPACING = 8.dp
private val DAY_TILE_SPACING = 4.dp
