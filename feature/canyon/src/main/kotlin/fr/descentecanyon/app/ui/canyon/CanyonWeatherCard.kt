package fr.descentecanyon.app.ui.canyon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.RainOutlook
import fr.descentecanyon.app.domain.model.WeatherLocationSource
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Weather at the canyon, in the order a decision is made: storm warning, rain fallen and coming
 * (the main driver of the flow), the next days, then daylight and where the forecast comes from.
 */
@Composable
fun CanyonWeatherCard(weather: CanyonWeather?, isLoading: Boolean, error: String?, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.weather_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            when {
                // The forecast saved is shown while the fresh one downloads.
                weather != null -> WeatherContent(weather, isRefreshing = isLoading)

                isLoading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(text = stringResource(R.string.weather_loading), style = MaterialTheme.typography.bodyMedium)
                }

                else -> Text(
                    text = error?.takeIf { it.isNotBlank() } ?: stringResource(R.string.weather_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun WeatherContent(weather: CanyonWeather, isRefreshing: Boolean) {
    // Times of the forecast are those of the weather point.
    val now = remember(weather) { LocalDateTime.now(weather.zoneId) }
    val rain = remember(weather, now) { weather.rainAround(now) }
    if (weather.isFromCache) {
        CachedForecastLine(weather, isRefreshing = isRefreshing)
    }
    StormWarning(weather, now = now)
    WeatherSection(title = stringResource(R.string.weather_rain_section)) {
        RainTotals(rain)
        // Without any rain nor storm over the period, the totals say it all.
        if (rain.hours.any { it.precipitationMm > 0.0 || it.isThunderstorm() }) {
            RainTimelineChart(rain = rain, now = now)
        }
    }
    if (weather.daily.isNotEmpty()) {
        WeatherSection(title = stringResource(R.string.weather_days_section)) {
            DailyForecastRow(daily = weather.daily, today = now.toLocalDate())
        }
    }
    WeatherFooter(weather, now)
}

@Composable
private fun WeatherSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

/** Rain fallen, rain coming and its chance, over the hours the chart shows. */
@Composable
private fun RainTotals(rain: RainOutlook) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TILE_SPACING),
    ) {
        RainStatTile(
            label = stringResource(R.string.weather_rain_past_hours, rain.past.size),
            value = formatRainMm(rain.pastTotalMm),
            modifier = Modifier.weight(1f),
        )
        // A forecast read offline long after its download has no coming hour left.
        val nextHours = rain.next.size.takeIf { it > 0 } ?: CanyonWeather.FORECAST_HOURS
        RainStatTile(
            label = stringResource(R.string.weather_rain_next_hours, nextHours),
            value = if (rain.next.isEmpty()) "--" else formatRainMm(rain.nextTotalMm),
            modifier = Modifier.weight(1f),
        )
        RainStatTile(
            label = stringResource(R.string.weather_rain_chance_hours, nextHours),
            value = rain.maxProbabilityPercent?.let { "$it %" } ?: "--",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RainStatTile(label: String, value: String, modifier: Modifier = Modifier) {
    WeatherTile(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** Daylight of today, then the point the forecast is computed for and when it was downloaded. */
@Composable
private fun WeatherFooter(weather: CanyonWeather, now: LocalDateTime) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        weather.dayOf(now.toLocalDate())?.let { SunTimesLine(it) }
        val point = weatherSourceLabel(weather.target.source)
        Text(
            text = if (weather.isFromCache) {
                // The download time is already given at the top of the card.
                stringResource(R.string.weather_point, point)
            } else {
                val updatedAt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                    .withLocale(LocalLocale.current.platformLocale)
                    .format(weather.fetchedAt.atZone(weather.zoneId))
                stringResource(R.string.weather_point_updated, point, updatedAt)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun weatherSourceLabel(source: WeatherLocationSource): String = stringResource(
    when (source) {
        WeatherLocationSource.WATERSHED_CENTER -> R.string.weather_source_watershed_center
        WeatherLocationSource.ENTRY -> R.string.weather_source_entry
        WeatherLocationSource.UPSTREAM_PARKING -> R.string.weather_source_upstream_parking
        WeatherLocationSource.EXIT -> R.string.weather_source_exit
        WeatherLocationSource.DOWNSTREAM_PARKING -> R.string.weather_source_downstream_parking
        WeatherLocationSource.REMARKABLE_POINT -> R.string.weather_source_remarkable_point
        WeatherLocationSource.ESCAPE -> R.string.weather_source_escape
        WeatherLocationSource.UNKNOWN -> R.string.weather_source_unknown
    },
)
