package fr.descentecanyon.app.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.LogbookBilan
import fr.descentecanyon.app.domain.model.LogbookCount
import fr.descentecanyon.app.ui.components.debitLevelColor
import fr.descentecanyon.app.ui.components.debitLevelShortLabel
import fr.descentecanyon.app.ui.components.formatDurationMinutes
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcMetricTile
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.LocalDcShapes

/** Figures of the logbook: totals, years, places, ratings, canyons done again, flows and companions. */
@Composable
internal fun LogbookBilanSection(bilan: LogbookBilan, onCanyonClick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DcMetricTile(stringResource(R.string.logbook_tab_outings), bilan.stats.outings.toString(), Modifier.weight(1f))
            DcMetricTile(stringResource(R.string.logbook_tab_canyons), bilan.stats.canyons.toString(), Modifier.weight(1f))
            if (bilan.timedOutings > 0) {
                DcMetricTile(
                    stringResource(R.string.logbook_bilan_time),
                    // The tile's monospaced digits make a no-break space as wide as a digit: plain spaces read better there.
                    formatDurationMinutes(bilan.totalDurationMinutes).replace(' ', ' '),
                    Modifier.weight(1f),
                )
            }
        }
        if (bilan.timedOutings > 0) {
            Text(
                pluralStringResource(R.plurals.logbook_bilan_timed, bilan.timedOutings, bilan.timedOutings),
                style = MaterialTheme.typography.bodySmall,
                color = LocalDcColors.current.textSecondary,
            )
        }

        if (bilan.years.isNotEmpty()) {
            BilanCard(stringResource(R.string.logbook_bilan_years)) {
                val max = bilan.years.maxOf { it.outings }
                bilan.years.forEach { year ->
                    BarRow(
                        label = year.year.toString(),
                        value = listOf(
                            pluralStringResource(R.plurals.logbook_stats, year.outings, year.outings),
                            pluralStringResource(R.plurals.logbook_stats_canyons, year.canyons, year.canyons),
                        ).joinToString(" · "),
                        fraction = year.outings.toFloat() / max,
                        labelWidth = YEAR_LABEL_WIDTH,
                        valueWidth = YEAR_VALUE_WIDTH,
                    )
                }
            }
        }
        CountsCard(stringResource(R.string.logbook_bilan_countries), bilan.countries)
        CountsCard(stringResource(R.string.logbook_bilan_areas), bilan.areas.take(MAX_ROWS))
        CountsCard(stringResource(R.string.logbook_bilan_ratings), bilan.verticalRatings)
        bilan.hardest?.let { hardest ->
            BilanCard(stringResource(R.string.logbook_bilan_hardest)) {
                Text(
                    listOfNotNull(hardest.canyonName, bilan.hardestRating).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable { onCanyonClick(hardest.canyonId) }.padding(vertical = 4.dp),
                )
            }
        }
        if (bilan.repeated.isNotEmpty()) {
            BilanCard(stringResource(R.string.logbook_bilan_repeated)) {
                bilan.repeated.take(MAX_ROWS).forEach { canyon ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onCanyonClick(canyon.canyonId) }.padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(canyon.canyonName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            pluralStringResource(R.plurals.logbook_card_count, canyon.outings, canyon.outings),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalDcColors.current.textSecondary,
                        )
                    }
                }
            }
        }
        if (bilan.levels.isNotEmpty()) {
            BilanCard(stringResource(R.string.logbook_bilan_flows)) {
                val max = bilan.levels.maxOf { it.second }
                bilan.levels.forEach { (level, outings) ->
                    BarRow(
                        label = debitLevelShortLabel(level),
                        value = outings.toString(),
                        fraction = outings.toFloat() / max,
                        color = debitLevelColor(level),
                    )
                }
            }
        }
        CountsCard(stringResource(R.string.logbook_bilan_companions), bilan.companions.take(MAX_ROWS))
    }
}

@Composable
private fun CountsCard(title: String, counts: List<LogbookCount>) {
    if (counts.isEmpty()) return
    BilanCard(title) {
        val max = counts.maxOf { it.outings }
        counts.forEach { count -> BarRow(label = count.label, value = count.outings.toString(), fraction = count.outings.toFloat() / max) }
    }
}

@Composable
private fun BilanCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    DcCard(modifier = Modifier.fillMaxWidth(), contentPadding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** A label, a bar as long as its share of the largest value, and the value. */
@Composable
private fun BarRow(
    label: String,
    value: String,
    fraction: Float,
    color: Color = LocalDcColors.current.water,
    labelWidth: Dp = LABEL_WIDTH,
    valueWidth: Dp = VALUE_WIDTH,
) {
    val colors = LocalDcColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(labelWidth),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .background(colors.borderSubtle.copy(alpha = 0.5f), LocalDcShapes.current.pill),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0.04f, 1f))
                    .background(color, LocalDcShapes.current.pill),
            )
        }
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(valueWidth),
        )
    }
}

private const val MAX_ROWS = 8
private val LABEL_WIDTH = 112.dp
private val VALUE_WIDTH = 32.dp
private val YEAR_LABEL_WIDTH = 44.dp
private val YEAR_VALUE_WIDTH = 168.dp
