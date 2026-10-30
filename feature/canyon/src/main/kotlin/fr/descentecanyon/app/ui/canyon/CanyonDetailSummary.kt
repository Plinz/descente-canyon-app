package fr.descentecanyon.app.ui.canyon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.Regulation
import fr.descentecanyon.app.ui.components.CotationBadge
import fr.descentecanyon.app.ui.components.CotationGuideDialog
import fr.descentecanyon.app.ui.components.ForbiddenBadge
import fr.descentecanyon.app.ui.components.InterestStars
import fr.descentecanyon.app.ui.components.canyonDurationText
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcCardVariant
import fr.descentecanyon.app.ui.design.DcMetricTile
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.LocalDcShapes
import fr.descentecanyon.app.ui.design.LocalDcSpacing
import fr.descentecanyon.app.ui.design.LocalDcTypography
import fr.descentecanyon.app.ui.test.TestTags
import java.util.Locale

@Composable
internal fun SummaryCard(detail: CanyonDetail, onPersistedPhotoMissing: (Long) -> Unit, modifier: Modifier = Modifier) {
    val canyon = detail.canyon
    val heroPhoto = detail.photos.firstOrNull()
    val colors = LocalDcColors.current
    val shapes = LocalDcShapes.current
    val spacing = LocalDcSpacing.current
    var showCotationGuide by rememberSaveable { mutableStateOf(false) }
    if (showCotationGuide) CotationGuideDialog(cotation = canyon.cotation, onDismiss = { showCotationGuide = false })

    DcCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screenHorizontal, vertical = spacing.sm),
        variant = DcCardVariant.Elevated,
        contentPadding = 0.dp,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp),
            ) {
                if (heroPhoto != null) {
                    HeroPhoto(
                        photo = heroPhoto,
                        onPersistedPhotoMissing = onPersistedPhotoMissing,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(colors.waterDeep, colors.backgroundElevated, colors.rock.copy(alpha = 0.72f)),
                                ),
                            ),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, colors.surfacePhotoScrim),
                                startY = 40f,
                            ),
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (canyon.isForbidden) {
                            ForbiddenBadge()
                        } else {
                            CotationBadge(
                                cotation = canyon.cotation,
                                onClick = { showCotationGuide = true }.takeIf { canyon.cotation.isNotBlank() },
                                modifier = Modifier.testTag(TestTags.COTATION_BADGE),
                            )
                        }
                        canyon.interet?.let { interest ->
                            // Over the photo scrim: light text, readable on any picture.
                            InterestStars(interest = interest, textColor = colors.snow, emptyStarColor = colors.snow.copy(alpha = 0.6f))
                        }
                    }
                    Text(
                        text = canyon.nom,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.snow,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = colors.waterMist,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${canyon.commune} - ${canyon.pays}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.waterMist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                heroPhoto?.auteur?.takeIf { it.isNotBlank() }?.let { author ->
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(spacing.md),
                        shape = shapes.pill,
                        color = colors.surfacePhotoScrim.copy(alpha = 0.72f),
                        contentColor = colors.snow,
                    ) {
                        Text(
                            text = author,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            val regulationsInForce = detail.regulations.filter { it.isInForce }
            if (regulationsInForce.isNotEmpty()) {
                RegulationsInForce(
                    regulations = regulationsInForce,
                    modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, top = spacing.lg),
                )
            }
            SummaryStatsGrid(
                detail = detail,
                modifier = Modifier.padding(spacing.lg),
            )
        }
    }
}

/** The texts in force, before anything else: the full history stays in the topo. */
@Composable
private fun RegulationsInForce(regulations: List<Regulation>, modifier: Modifier = Modifier) {
    val colors = LocalDcColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        regulations.forEach { regulation ->
            val tint = if (regulation.forbids) MaterialTheme.colorScheme.error else colors.warning
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Gavel, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.regulation_in_force_line, regulation.action.orEmpty().ifBlank { "-" }, regulation.title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
            }
        }
    }
}

@Composable
private fun SummaryStatsGrid(detail: CanyonDetail, modifier: Modifier = Modifier) {
    val canyon = detail.canyon

    // In pairs, each in the same place from one canyon to the next: a row when one of its two is known.
    val parcoursRows = listOf(
        listOf(
            SummaryStat(stringResource(R.string.altitude), canyon.altitudeDepart?.let { "${it}m" }),
            SummaryStat(stringResource(R.string.elevation), canyon.denivele?.let { "${it}m" }),
        ),
        listOf(
            SummaryStat(stringResource(R.string.length), canyon.longueur?.let { "${it}m" }),
            SummaryStat(stringResource(R.string.max_waterfall), canyon.cascadeMax?.let { "${it}m" }),
        ),
        listOf(
            SummaryStat(stringResource(R.string.rope), canyon.cordeMin?.let { "${it}m" }),
            SummaryStat(stringResource(R.string.watershed_area), detail.watershed?.areaKm2?.let(::formatAreaKm2)),
        ),
    ).filter { row -> row.any { !it.value.isNullOrBlank() } }

    val timeStats = listOf(
        SummaryStat(stringResource(R.string.approach), canyonDurationText(canyon.tempsApproche)),
        SummaryStat(stringResource(R.string.descent), canyonDurationText(canyon.tempsDescente)),
        SummaryStat(stringResource(R.string.return_path), canyonDurationText(canyon.tempsRetour)),
    ).filter { !it.value.isNullOrBlank() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (parcoursRows.isNotEmpty()) {
            SummaryMetricSection(
                title = stringResource(R.string.canyon_summary_parcours),
                rows = parcoursRows,
            )
        }
        if (timeStats.isNotEmpty()) {
            SummaryTimesSection(
                title = stringResource(R.string.canyon_summary_timing),
                stats = timeStats,
            )
        }
    }
}

/** Where the canyon is: reference information, shown at the top of the topo. */
@Composable
internal fun LocationCard(detail: CanyonDetail, modifier: Modifier = Modifier) {
    val canyon = detail.canyon
    val locationStats = listOf(
        SummaryStat(
            stringResource(R.string.communes),
            canyon.communes.takeIf { it.isNotEmpty() }?.joinToString(),
        ),
        SummaryStat(stringResource(R.string.region), canyon.region),
        SummaryStat(stringResource(R.string.department), canyon.departement),
        SummaryStat(stringResource(R.string.massif), canyon.massif),
        SummaryStat(stringResource(R.string.basin), canyon.bassin),
        SummaryStat(stringResource(R.string.watercourse), canyon.coursEau),
    ).filter { !it.value.isNullOrBlank() }
    if (locationStats.isEmpty()) return

    DcCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = 16.dp,
    ) {
        SummarySection(
            title = stringResource(R.string.location),
            stats = locationStats,
        )
    }
}

private data class SummaryStat(val label: String, val value: String?)

private fun formatAreaKm2(areaKm2: Double): String {
    val precision = if (areaKm2 >= 100.0) {
        "%.0f"
    } else if (areaKm2 >= 10.0) {
        "%.1f"
    } else {
        "%.2f"
    }
    return String.format(Locale.getDefault(), "$precision km²", areaKm2)
}

@Composable
private fun SummarySection(title: String, stats: List<SummaryStat>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                stats.forEachIndexed { index, stat ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stat.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stat.value ?: "-",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (index != stats.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryMetricSection(title: String, rows: List<List<SummaryStat>>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricSectionTitle(title)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { rowStats ->
                // The tiles of a row as tall as each other, a label on two lines or not.
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowStats.forEach { stat ->
                        DcMetricTile(
                            label = stat.label,
                            value = stat.value?.takeIf { it.isNotBlank() } ?: "–",
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/** The times of the outing in one tile, in the order of the day: approach › descent › return. */
@Composable
private fun SummaryTimesSection(title: String, stats: List<SummaryStat>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricSectionTitle(title)
        DcCard(modifier = Modifier.fillMaxWidth(), variant = DcCardVariant.Condition, contentPadding = 12.dp) {
            StepsRow(stats)
        }
    }
}

@Composable
private fun MetricSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = LocalDcColors.current.textSecondary,
    )
}

/**
 * The [steps] side by side and centered, a chevron between two, sharing the width as evenly as their
 * texts allow; short of room, the longest values wrap between their words. When even that does not
 * fit (a narrow screen, long labels), one step a line: its label, and its value at the end.
 */
@Composable
private fun StepsRow(steps: List<SummaryStat>) {
    val colors = LocalDcColors.current
    val typography = LocalDcTypography.current
    Layout(
        content = {
            steps.forEachIndexed { index, stat ->
                Text(text = stat.label.uppercase(), style = typography.metricLabel, color = colors.textMuted, maxLines = 1)
                Text(text = stat.value ?: "-", style = typography.metricValue, color = colors.textPrimary, textAlign = TextAlign.Center)
                if (index < steps.lastIndex) {
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
                }
            }
        },
    ) { measurables, constraints ->
        val labels = measurables.filterIndexed { index, _ -> index % 3 == 0 }
        val values = measurables.filterIndexed { index, _ -> index % 3 == 1 }
        val chevrons = measurables.filterIndexed { index, _ -> index % 3 == 2 }.map { it.measure(Constraints()) }
        val gap = STEP_GAP.roundToPx()
        val room = (constraints.maxWidth - chevrons.sumOf { it.width + 2 * gap }).coerceAtLeast(0)
        val labelWidths = labels.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val valueWords = values.map { it.minIntrinsicWidth(Constraints.Infinity) }
        val preferred = values.mapIndexed { index, value -> maxOf(labelWidths[index], value.maxIntrinsicWidth(Constraints.Infinity)) }
        val least = valueWords.zip(labelWidths, ::maxOf)
        if (least.sum() > room) {
            // One step a line.
            val labelPlaceables = labels.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
            val valuePlaceables = values.mapIndexed { index, value ->
                value.measure(Constraints(maxWidth = (constraints.maxWidth - labelPlaceables[index].width - 2 * gap).coerceAtLeast(0)))
            }
            val heights = labelPlaceables.zip(valuePlaceables) { label, value -> maxOf(label.height, value.height) }
            val lineGap = STEP_LINE_GAP.roundToPx()
            return@Layout layout(constraints.maxWidth, heights.sum() + lineGap * (heights.size - 1)) {
                var y = 0
                heights.forEachIndexed { index, height ->
                    labelPlaceables[index].placeRelative(0, y + (height - labelPlaceables[index].height) / 2)
                    valuePlaceables[index].let { it.placeRelative(constraints.maxWidth - it.width, y + (height - it.height) / 2) }
                    y += height + lineGap
                }
            }
        }
        val widths = if (preferred.sum() <= room) {
            evened(preferred, room)
        } else {
            val extra = room - least.sum()
            val wanted = preferred.sum() - least.sum()
            least.zip(preferred) { low, high -> low + (high - low) * extra / wanted }
        }
        val labelPlaceables = labels.zip(widths) { label, width -> label.measure(Constraints(maxWidth = width)) }
        val valuePlaceables = values.zip(widths) { value, width -> value.measure(Constraints(maxWidth = width)) }
        val labelHeight = labelPlaceables.maxOf { it.height }
        // On the first line of the values.
        val chevronCenter = labelHeight + typography.metricValue.lineHeight.roundToPx() / 2
        val spread = if (chevrons.isEmpty()) 0 else (room - widths.sum()) / (2 * chevrons.size)
        layout(constraints.maxWidth, labelHeight + valuePlaceables.maxOf { it.height }) {
            var x = 0
            widths.forEachIndexed { index, width ->
                labelPlaceables[index].placeRelative(x + (width - labelPlaceables[index].width) / 2, 0)
                valuePlaceables[index].placeRelative(x + (width - valuePlaceables[index].width) / 2, labelHeight)
                x += width
                chevrons.getOrNull(index)?.let { chevron ->
                    x += gap + spread
                    chevron.placeRelative(x, chevronCenter - chevron.height / 2)
                    x += chevron.width + gap + spread
                }
            }
        }
    }
}

/** [widths] grown to share [room] as evenly as they can: the narrow ones widened first, none narrowed. */
private fun evened(widths: List<Int>, room: Int): List<Int> {
    val widest = widths.sortedDescending()
    var taken = 0
    widest.forEachIndexed { index, width ->
        val level = (room - taken) / (widest.size - index)
        if (width <= level) return widths.map { maxOf(it, level) }
        taken += width
    }
    return widths
}

private val STEP_GAP = 4.dp
private val STEP_LINE_GAP = 4.dp
