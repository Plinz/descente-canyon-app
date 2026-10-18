package fr.descentecanyon.app.ui.canyon

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.DailyDebitPrediction
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.PredictedDebitLevel
import fr.descentecanyon.app.domain.model.PredictionConfidence
import fr.descentecanyon.app.ui.components.debitRangeLabel
import fr.descentecanyon.app.ui.theme.DebitCorrect
import fr.descentecanyon.app.ui.theme.DebitFilet
import fr.descentecanyon.app.ui.theme.DebitGros
import fr.descentecanyon.app.ui.theme.DebitInconnu
import fr.descentecanyon.app.ui.theme.DebitSec
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@Composable
fun CanyonDebitPredictionCard(
    predictions: CanyonDebitPredictions?,
    isLoading: Boolean,
    error: String?,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier,
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.prediction_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!expanded) {
                        PredictionSummaryLine(
                            predictions = predictions,
                            isLoading = isLoading,
                            error = error,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.canyon_summary_collapse else R.string.canyon_summary_expand,
                    ),
                )
            }

            if (predictions?.isFromCache == true) {
                OfflineEstimateLine(predictions, isRefreshing = isLoading)
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        // The estimate saved stays on screen while the fresh one is computed.
                        predictions != null -> {
                            predictions.predictions.forEachIndexed { index, prediction ->
                                if (index > 0) {
                                    HorizontalDivider()
                                }
                                PredictionRow(
                                    prediction = prediction,
                                    ordinalCutpoints = predictions.ordinalCutpoints,
                                    showScaleLabels = index == 0,
                                )
                            }
                        }

                        isLoading || error == null -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(strokeWidth = 2.dp)
                                Text(
                                    text = stringResource(R.string.prediction_loading),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        else -> {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    TextButton(
                        onClick = onInfoClick,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(text = stringResource(R.string.prediction_info_cta))
                    }
                }
            }
        }
    }
}

/** Shown when the network is unavailable and the last estimate computed is displayed. */
@Composable
private fun OfflineEstimateLine(predictions: CanyonDebitPredictions, isRefreshing: Boolean) {
    val computedAt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(LocalLocale.current.platformLocale)
        .format(predictions.computedAt.atZone(ZoneId.systemDefault()))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(
                if (isRefreshing) R.string.prediction_refreshing_cached else R.string.prediction_offline_cached,
                computedAt,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Collapsed card: today and tomorrow at a glance, or the loading or error state. */
@Composable
private fun PredictionSummaryLine(predictions: CanyonDebitPredictions?, isLoading: Boolean, error: String?) {
    if (predictions == null) {
        val shownError = error?.takeIf { !isLoading }
        Text(
            text = shownError?.ifBlank { stringResource(R.string.prediction_unavailable) }
                ?: stringResource(R.string.prediction_loading),
            style = MaterialTheme.typography.bodySmall,
            color = if (shownError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val todayPrediction = predictions.predictions.firstOrNull { it.horizonDays == 0 }
        ?: predictions.predictions.firstOrNull()
    val tomorrowPrediction = predictions.predictions.firstOrNull { it.horizonDays == 1 }
    // Same height for both tiles when one value wraps ("Débit correct probable" on a narrow phone).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        todayPrediction?.let { prediction ->
            PredictionCompactTile(
                label = stringResource(R.string.prediction_today),
                value = predictionSummaryValue(prediction),
                color = predictionSummaryColor(prediction),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
        tomorrowPrediction?.let { prediction ->
            PredictionCompactTile(
                label = stringResource(R.string.prediction_tomorrow),
                value = predictionSummaryValue(prediction),
                color = predictionSummaryColor(prediction),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
        if (todayPrediction != null && tomorrowPrediction == null) {
            Box(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PredictionCompactTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.14f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.42f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = color,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun predictionSummaryValue(prediction: DailyDebitPrediction): String = prediction.likelyRange()?.let { debitRangeLabel(it) }
    ?: prediction.ordinalLevel?.let { ordinalLevelTitle(it) }
    ?: levelLabel(prediction.level)

@Composable
private fun predictionSummaryColor(prediction: DailyDebitPrediction): Color =
    prediction.ordinalLevel?.let { ordinalColor(it) } ?: levelColor(prediction.level)

/** One day: the shown level or range, the gauge, then its confidence and the high-flow risk. */
@Composable
private fun PredictionRow(
    prediction: DailyDebitPrediction,
    ordinalCutpoints: List<Double>,
    showScaleLabels: Boolean,
    modifier: Modifier = Modifier,
) {
    val ordinalScore = prediction.ordinalScore
    val confidence = prediction.confidence()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (prediction.horizonDays) {
                    0 -> stringResource(R.string.prediction_today)
                    1 -> stringResource(R.string.prediction_tomorrow)
                    else -> stringResource(R.string.prediction_day_after_tomorrow)
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = predictionSummaryValue(prediction),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = predictionSummaryColor(prediction),
                textAlign = TextAlign.End,
            )
        }

        if (prediction.ordinalLevel != null && ordinalScore != null) {
            DebitOrdinalGauge(
                score = ordinalScore,
                ordinalCutpoints = ordinalCutpoints,
                showLabels = showScaleLabels,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            confidence?.let { ConfidenceLine(it) }
            HighFlowRiskLine(prediction)
        }
    }
}

/**
 * Probability of a high flow (GROS or above). Shown when it is not negligible, highlighted when the
 * selected policy flags the day as a high-flow risk even if the most likely level is lower.
 */
@Composable
private fun HighFlowRiskLine(prediction: DailyDebitPrediction) {
    val probability = prediction.probabilities[PredictedDebitLevel.HIGH] ?: return
    val flagged = prediction.level == PredictedDebitLevel.HIGH
    if (probability < HIGH_FLOW_RISK_DISPLAY_THRESHOLD && !flagged) return
    Text(
        text = stringResource(R.string.prediction_high_flow_risk, (probability * 100).roundToInt()),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (flagged) FontWeight.SemiBold else FontWeight.Normal,
        color = if (flagged) DebitGros else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val HIGH_FLOW_RISK_DISPLAY_THRESHOLD = 0.10

@Composable
private fun ConfidenceLine(confidence: PredictionConfidence) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(confidenceColor(confidence)),
        )
        Text(
            text = confidenceLabel(confidence),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The six levels as equal segments with the marker at the display score; the level names below when [showLabels]. */
@Composable
private fun DebitOrdinalGauge(score: Double, ordinalCutpoints: List<Double>, showLabels: Boolean, modifier: Modifier = Modifier) {
    val trackShape = RoundedCornerShape(999.dp)
    val markerWidth = 6.dp
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(trackShape)
                    .border(1.dp, gaugeTrackBorderColor(), trackShape),
            ) {
                ORDINAL_GAUGE_LEVELS.forEach { gaugeLevel ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(ordinalColor(gaugeLevel)),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (maxWidth - markerWidth) * ordinalGaugePositionFraction(score, ordinalCutpoints))
                    .width(markerWidth)
                    .height(20.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .border(1.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(999.dp)),
            )
        }
        if (showLabels) {
            Row(modifier = Modifier.fillMaxWidth()) {
                ORDINAL_GAUGE_LEVELS.forEach { gaugeLevel ->
                    Text(
                        text = ordinalLevelShortLabel(gaugeLevel),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun confidenceLabel(confidence: PredictionConfidence): String = when (confidence) {
    PredictionConfidence.HIGH -> stringResource(R.string.prediction_confidence_high)
    PredictionConfidence.MEDIUM -> stringResource(R.string.prediction_confidence_medium)
    PredictionConfidence.LOW -> stringResource(R.string.prediction_confidence_low)
}

@Composable
private fun confidenceColor(confidence: PredictionConfidence): Color = when (confidence) {
    PredictionConfidence.HIGH -> DebitCorrect
    PredictionConfidence.MEDIUM -> DebitGros
    PredictionConfidence.LOW -> MaterialTheme.colorScheme.error
}

@Composable
private fun levelLabel(level: PredictedDebitLevel): String = when (level) {
    PredictedDebitLevel.LOW -> stringResource(R.string.prediction_level_low)
    PredictedDebitLevel.MEDIUM -> stringResource(R.string.prediction_level_medium)
    PredictedDebitLevel.HIGH -> stringResource(R.string.prediction_level_high)
}

@Composable
private fun ordinalLevelTitle(level: NiveauDebit): String = when (level) {
    NiveauDebit.SEC -> stringResource(R.string.prediction_ordinal_sec)
    NiveauDebit.FILET -> stringResource(R.string.prediction_ordinal_filet)
    NiveauDebit.CORRECT -> stringResource(R.string.prediction_ordinal_correct)
    NiveauDebit.GROS -> stringResource(R.string.prediction_ordinal_gros)
    NiveauDebit.TRES_GROS -> stringResource(R.string.prediction_ordinal_tres_gros)
    NiveauDebit.CRUE -> stringResource(R.string.prediction_ordinal_crue)
    NiveauDebit.INCONNU -> stringResource(R.string.debit_level_inconnu)
}

@Composable
private fun ordinalLevelShortLabel(level: NiveauDebit): String = when (level) {
    NiveauDebit.SEC -> stringResource(R.string.debit_level_sec)
    NiveauDebit.FILET -> stringResource(R.string.debit_level_filet_short)
    NiveauDebit.CORRECT -> stringResource(R.string.debit_level_correct)
    NiveauDebit.GROS -> stringResource(R.string.debit_level_gros)
    NiveauDebit.TRES_GROS -> stringResource(R.string.debit_level_tres_gros)
    NiveauDebit.CRUE -> stringResource(R.string.debit_level_crue_short)
    NiveauDebit.INCONNU -> stringResource(R.string.debit_level_inconnu)
}

@Composable
private fun levelColor(level: PredictedDebitLevel): Color = when (level) {
    PredictedDebitLevel.LOW -> MaterialTheme.colorScheme.primary
    PredictedDebitLevel.MEDIUM -> DebitCorrect
    PredictedDebitLevel.HIGH -> DebitVeryHighRed
}

private fun ordinalColor(level: NiveauDebit): Color = when (level) {
    NiveauDebit.SEC -> DebitSec
    NiveauDebit.FILET -> DebitFilet
    NiveauDebit.CORRECT -> DebitCorrect
    NiveauDebit.GROS -> DebitGros
    NiveauDebit.TRES_GROS -> DebitVeryHighRed
    NiveauDebit.CRUE -> Color.Black
    NiveauDebit.INCONNU -> DebitInconnu
}

internal fun ordinalGaugePositionFraction(score: Double, ordinalCutpoints: List<Double> = emptyList()): Float {
    if (ordinalCutpoints.size == ORDINAL_GAUGE_LEVELS.size - 1 && ordinalCutpoints.zipWithNext().all { (left, right) -> left < right }) {
        return ordinalGaugePositionFractionFromCutpoints(score, ordinalCutpoints)
    }
    val categoryCount = ORDINAL_GAUGE_LEVELS.size.toFloat()
    return ((score.coerceIn(0.0, 5.0).toFloat() + 0.5f) / categoryCount).coerceIn(0f, 1f)
}

private fun ordinalGaugePositionFractionFromCutpoints(score: Double, ordinalCutpoints: List<Double>): Float {
    val categoryCount = ORDINAL_GAUGE_LEVELS.size
    val bounds = listOf(0.0) + ordinalCutpoints + listOf(5.0)
    val clampedScore = score.coerceIn(bounds.first(), bounds.last())
    val index = ordinalCutpoints.indexOfFirst { clampedScore < it }.takeIf { it >= 0 } ?: (categoryCount - 1)
    val lower = bounds[index]
    val upper = bounds[index + 1]
    val localFraction = if (upper > lower) {
        ((clampedScore - lower) / (upper - lower)).coerceIn(0.0, 1.0)
    } else {
        0.5
    }
    return ((index + localFraction) / categoryCount).toFloat().coerceIn(0f, 1f)
}

@Composable
private fun gaugeTrackBorderColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f)

private val ORDINAL_GAUGE_LEVELS = listOf(
    NiveauDebit.SEC,
    NiveauDebit.FILET,
    NiveauDebit.CORRECT,
    NiveauDebit.GROS,
    NiveauDebit.TRES_GROS,
    NiveauDebit.CRUE,
)

private val DebitVeryHighRed = Color(0xFFE53935)
