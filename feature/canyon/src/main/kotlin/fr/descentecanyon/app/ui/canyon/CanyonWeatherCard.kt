package fr.descentecanyon.app.ui.canyon

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.WeatherLocationSource
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CanyonWeatherCard(
    weather: CanyonWeather?,
    isLoading: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.weather_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    WeatherSummaryLine(
                        weather = weather,
                        isLoading = isLoading,
                        error = error,
                        expanded = expanded,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.canyon_summary_collapse else R.string.canyon_summary_expand,
                    ),
                )
            }

            weather?.let { current ->
                if (current.isFromCache) {
                    CachedForecastLine(current)
                }
                StormWarning(
                    current,
                    now = LocalDateTime.now(
                        runCatching {
                            ZoneId.of(current.timezone)
                        }.getOrDefault(ZoneId.systemDefault()),
                    ),
                )
                if (current.daily.isNotEmpty()) {
                    DailyForecastRow(daily = current.daily, today = today)
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    weather?.let {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            it.dayOf(today)?.let { day -> SunTimesLine(day) }
                            Text(
                                text = stringResource(
                                    R.string.weather_target_value,
                                    weatherSourceLabel(it),
                                    formatCoordinates(it.target.latitude, it.target.longitude),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.weather_updated_at, formatUpdatedAt(it)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    when {
                        isLoading && weather == null -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    text = stringResource(R.string.weather_loading),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        weather != null -> {
                            Text(
                                text = stringResource(R.string.weather_past_section),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MetricRow(
                                items = listOf(
                                    stringResource(R.string.weather_last_24h) to formatMillimeters(weather.past24HoursPrecipitationMm),
                                    stringResource(R.string.weather_last_48h) to formatMillimeters(weather.past48HoursPrecipitationMm),
                                    stringResource(R.string.weather_last_72h) to formatMillimeters(weather.past72HoursPrecipitationMm),
                                ),
                            )

                            Text(
                                text = stringResource(R.string.weather_future_section),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MetricRow(
                                items = listOf(
                                    stringResource(R.string.weather_next_24h) to formatMillimeters(weather.next24HoursPrecipitationMm),
                                    stringResource(R.string.weather_next_48h) to formatMillimeters(weather.next48HoursPrecipitationMm),
                                ),
                            )

                            MetricRow(
                                items = listOf(
                                    stringResource(R.string.weather_max_hourly_past) to
                                        formatMillimeters(weather.maxHourlyPrecipitationPast72HoursMm),
                                    stringResource(R.string.weather_probability_next_24h) to
                                        formatProbability(weather.maxPrecipitationProbabilityNext24Hours),
                                ),
                            )
                        }

                        error != null -> {
                            Text(
                                text = error.ifBlank { stringResource(R.string.weather_unavailable) },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        else -> {
                            Text(
                                text = stringResource(R.string.weather_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherSummaryLine(weather: CanyonWeather?, isLoading: Boolean, error: String?, expanded: Boolean) {
    if (weather != null && !expanded) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WeatherCompactTile(
                label = stringResource(R.string.weather_compact_past_72h),
                value = formatMillimeters(weather.past72HoursPrecipitationMm),
                modifier = Modifier.weight(1f),
            )
            WeatherCompactTile(
                label = stringResource(R.string.weather_compact_next_48h),
                value = formatMillimeters(weather.next48HoursPrecipitationMm),
                modifier = Modifier.weight(1f),
            )
            WeatherCompactTile(
                label = stringResource(R.string.weather_compact_rain_chance),
                value = formatProbability(weather.maxPrecipitationProbabilityNext24Hours),
                modifier = Modifier.weight(1f),
            )
        }
        return
    }

    val text = when {
        isLoading && weather == null -> stringResource(R.string.weather_loading)

        weather != null -> stringResource(
            R.string.weather_collapsed_summary,
            formatMillimeters(weather.past72HoursPrecipitationMm),
            formatMillimeters(weather.next48HoursPrecipitationMm),
        )

        error != null -> error.ifBlank { stringResource(R.string.weather_unavailable) }

        else -> stringResource(R.string.weather_loading)
    }

    val color = when {
        error != null && !isLoading && weather == null -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = if (expanded) 2 else 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun WeatherCompactTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MetricRow(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (label, value) ->
            WeatherMetricTile(
                label = label,
                value = value,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun WeatherMetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun weatherSourceLabel(weather: CanyonWeather): String = stringResource(
    when (weather.target.source) {
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

private fun formatMillimeters(value: Double): String {
    val pattern = if (value >= 10.0) "%.0f mm" else "%.1f mm"
    return String.format(Locale.getDefault(), pattern, value)
}

private fun formatProbability(value: Int?): String = value?.let { "$it%" } ?: "--"

private fun formatCoordinates(latitude: Double, longitude: Double): String =
    String.format(Locale.getDefault(), "%.4f, %.4f", latitude, longitude)

private fun formatUpdatedAt(weather: CanyonWeather): String {
    val zoneId = runCatching { ZoneId.of(weather.timezone) }.getOrDefault(ZoneId.systemDefault())
    return DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.getDefault())
        .format(weather.fetchedAt.atZone(zoneId))
}
