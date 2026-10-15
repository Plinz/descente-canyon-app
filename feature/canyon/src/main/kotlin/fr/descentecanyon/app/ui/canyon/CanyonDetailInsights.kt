package fr.descentecanyon.app.ui.canyon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.MonthlyDebitProfile
import fr.descentecanyon.app.domain.model.NearbyCanyon
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ORDINAL_DEBIT_LEVELS
import fr.descentecanyon.app.domain.model.monthlyProfiles
import fr.descentecanyon.app.domain.usecase.GetNearbyCanyonsUseCase
import fr.descentecanyon.app.ui.components.DebitBadge
import fr.descentecanyon.app.ui.components.debitLevelColor
import fr.descentecanyon.app.ui.components.debitLevelLabel
import fr.descentecanyon.app.ui.components.relativeDayLabel
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcRiskBadge
import fr.descentecanyon.app.ui.design.DcRiskLevel
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.test.TestTags
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Canyons around this one, nearest first, with their last known flow: a plan B at a glance. */
@Composable
internal fun NearbyCanyonsSection(nearby: List<NearbyCanyon>, onCanyonClick: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (nearby.isEmpty()) return
    val colors = LocalDcColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag(TestTags.DETAIL_NEARBY_SECTION),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = stringResource(R.string.nearby_canyons_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.nearby_canyons_subtitle,
                    nearby.size,
                    nearby.size,
                    GetNearbyCanyonsUseCase.DEFAULT_RADIUS_KM.roundToInt(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(nearby, key = { it.summary.id }) { canyon ->
                NearbyCanyonCard(canyon = canyon, onClick = { onCanyonClick(canyon.summary.id) })
            }
        }
    }
}

@Composable
private fun NearbyCanyonCard(canyon: NearbyCanyon, onClick: () -> Unit) {
    val colors = LocalDcColors.current
    val summary = canyon.summary
    val distance = formatDistanceKm(canyon.distanceKm, LocalLocale.current.platformLocale)
    val level = summary.dernierDebit
    val reportDate = summary.dernierDebitDate
    val condition = when {
        summary.isForbidden -> stringResource(R.string.nearby_forbidden)
        level != null && reportDate != null -> "${debitLevelLabel(level)}, ${relativeDayLabel(reportDate)}"
        else -> stringResource(R.string.nearby_no_recent_report)
    }
    val description = stringResource(R.string.nearby_canyon_description, summary.nom, distance, condition)
    DcCard(
        onClick = onClick,
        modifier = Modifier
            .width(184.dp)
            .semantics { contentDescription = description }
            .testTag(TestTags.detailNearbyCanyon(summary.id)),
        contentPadding = 12.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = summary.nom,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    distance,
                    summary.cotation.takeIf { it.isNotBlank() },
                    summary.interet?.let { String.format(LocalLocale.current.platformLocale, "★ %.1f", it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                summary.isForbidden -> DcRiskBadge(text = stringResource(R.string.nearby_forbidden), level = DcRiskLevel.High)

                level != null && reportDate != null -> Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DebitBadge(niveau = level)
                    Text(
                        text = relativeDayLabel(reportDate),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                        maxLines = 1,
                    )
                }

                else -> Text(
                    text = stringResource(R.string.nearby_no_recent_report),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Share of each flow level among the reports of every calendar month: when the canyon usually has
 * water. Months with few reports are faded.
 */
@Composable
internal fun DebitSeasonalityCard(debits: List<Debit>, modifier: Modifier = Modifier) {
    val profiles = remember(debits) { debits.monthlyProfiles() }
    val total = profiles.sumOf { it.reportCount }
    if (total < MIN_REPORTS_FOR_SEASONALITY) return
    val colors = LocalDcColors.current
    val locale = LocalLocale.current.platformLocale

    DcCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag(TestTags.DETAIL_SEASONALITY),
        contentPadding = 14.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text(
                    text = stringResource(R.string.seasonality_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = pluralStringResource(R.plurals.seasonality_subtitle, total, total),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(118.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                profiles.forEach { profile ->
                    MonthColumn(profile = profile, locale = locale, modifier = Modifier.weight(1f))
                }
            }
            SeasonalityLegend()
            if (profiles.any { it.reportCount in 1 until FEW_REPORTS }) {
                Text(
                    text = stringResource(R.string.seasonality_few_reports),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun MonthColumn(profile: MonthlyDebitProfile, locale: Locale, modifier: Modifier = Modifier) {
    val monthName = profile.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
    val reports = pluralStringResource(R.plurals.seasonality_month_reports, profile.reportCount, profile.reportCount)
    val dominant = ORDINAL_DEBIT_LEVELS.maxByOrNull { profile.share(it) }?.takeIf { profile.reportCount > 0 }
    val details = if (dominant != null) {
        "$reports, " + stringResource(R.string.seasonality_mostly, debitLevelLabel(dominant), (profile.share(dominant) * 100).roundToInt())
    } else {
        reports
    }
    val description = stringResource(R.string.seasonality_month_description, monthName, details)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .alpha(if (profile.reportCount < FEW_REPORTS) 0.35f else 1f),
            verticalArrangement = Arrangement.Bottom,
        ) {
            // Highest levels on top, as on a gauge.
            ORDINAL_DEBIT_LEVELS.asReversed().forEach { level ->
                val share = profile.share(level).toFloat()
                if (share > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(share)
                            .background(debitLevelColor(level)),
                    )
                }
            }
        }
        Text(
            text = profile.month.getDisplayName(TextStyle.NARROW_STANDALONE, locale),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun SeasonalityLegend(modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ORDINAL_DEBIT_LEVELS.forEach { level -> LegendDot(level) }
    }
}

@Composable
private fun LegendDot(level: NiveauDebit) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(8.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(debitLevelColor(level)),
        )
        Text(
            text = shortDebitLevelLabel(level),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun shortDebitLevelLabel(level: NiveauDebit): String = when (level) {
    NiveauDebit.SEC -> stringResource(R.string.prediction_range_level_sec)
    NiveauDebit.FILET -> stringResource(R.string.prediction_range_level_filet)
    NiveauDebit.CORRECT -> stringResource(R.string.prediction_range_level_correct)
    NiveauDebit.GROS -> stringResource(R.string.prediction_range_level_gros)
    NiveauDebit.TRES_GROS -> stringResource(R.string.prediction_range_level_tres_gros)
    NiveauDebit.CRUE -> stringResource(R.string.prediction_range_level_crue)
    NiveauDebit.INCONNU -> stringResource(R.string.debit_level_inconnu)
}

internal fun formatDistanceKm(distanceKm: Double, locale: Locale): String = if (distanceKm < 10.0) {
    String.format(locale, "%.1f km", distanceKm)
} else {
    String.format(locale, "%.0f km", distanceKm)
}

/** Below this many reports, the monthly distribution says nothing useful. */
private const val MIN_REPORTS_FOR_SEASONALITY = 12

/** Months with fewer reports are faded. */
private const val FEW_REPORTS = 5
