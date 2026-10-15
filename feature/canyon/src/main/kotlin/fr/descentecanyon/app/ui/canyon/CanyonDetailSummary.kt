package fr.descentecanyon.app.ui.canyon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.ui.components.CotationBadge
import fr.descentecanyon.app.ui.components.ForbiddenBadge
import fr.descentecanyon.app.ui.components.InterestStars
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcCardVariant
import fr.descentecanyon.app.ui.design.DcMetricTile
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.LocalDcShapes
import fr.descentecanyon.app.ui.design.LocalDcSpacing
import java.util.Locale

@Composable
internal fun SummaryCard(detail: CanyonDetail, onPersistedPhotoMissing: (Long) -> Unit, modifier: Modifier = Modifier) {
    val canyon = detail.canyon
    val heroPhoto = detail.photos.firstOrNull()
    val colors = LocalDcColors.current
    val shapes = LocalDcShapes.current
    val spacing = LocalDcSpacing.current

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
                            CotationBadge(cotation = canyon.cotation)
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
            SummaryStatsGrid(
                detail = detail,
                modifier = Modifier.padding(spacing.lg),
            )
        }
    }
}

@Composable
private fun SummaryStatsGrid(detail: CanyonDetail, modifier: Modifier = Modifier) {
    val canyon = detail.canyon

    val parcoursStats = listOf(
        SummaryStat(stringResource(R.string.altitude), canyon.altitudeDepart?.let { "${it}m" }),
        SummaryStat(stringResource(R.string.elevation), canyon.denivele?.let { "${it}m" }),
        SummaryStat(stringResource(R.string.length), canyon.longueur?.let { "${it}m" }),
        SummaryStat(stringResource(R.string.max_waterfall), canyon.cascadeMax?.let { "${it}m" }),
        SummaryStat(stringResource(R.string.rope), canyon.cordeMin?.let { "${it}m" }),
    ).filter { !it.value.isNullOrBlank() }

    val timeStats = listOf(
        SummaryStat(stringResource(R.string.approach_time), canyon.tempsApproche),
        SummaryStat(stringResource(R.string.descent_time), canyon.tempsDescente),
        SummaryStat(stringResource(R.string.return_time), canyon.tempsRetour),
    ).filter { !it.value.isNullOrBlank() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (parcoursStats.isNotEmpty()) {
            SummaryMetricSection(
                title = stringResource(R.string.canyon_summary_parcours),
                stats = parcoursStats,
            )
        }
        if (timeStats.isNotEmpty()) {
            SummaryMetricSection(
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
        SummaryStat(
            stringResource(R.string.watershed_area),
            detail.watershed?.areaKm2?.let(::formatAreaKm2),
        ),
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
    return String.format(Locale.getDefault(), "$precision km2", areaKm2)
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
private fun SummaryMetricSection(title: String, stats: List<SummaryStat>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = LocalDcColors.current.textSecondary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            stats.chunked(2).forEach { rowStats ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowStats.forEach { stat ->
                        DcMetricTile(
                            label = stat.label,
                            value = stat.value ?: "-",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowStats.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
