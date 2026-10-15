package fr.descentecanyon.app.ui.canyon

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import fr.descentecanyon.app.ui.theme.DebitCorrect
import fr.descentecanyon.app.ui.theme.DebitFilet
import fr.descentecanyon.app.ui.theme.DebitGros
import fr.descentecanyon.app.ui.theme.DebitInconnu
import fr.descentecanyon.app.ui.theme.DebitSec
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
                horizontalArrangement = Arrangement.SpaceBetween,
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
                    PredictionSummaryLine(
                        predictions = predictions,
                        isLoading = isLoading,
                        error = error,
                        expanded = expanded,
                    )
                }
                if (expanded) {
                    TextButton(onClick = onInfoClick) {
                        Text(text = stringResource(R.string.prediction_info_cta))
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.canyon_summary_collapse else R.string.canyon_summary_expand,
                    ),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        isLoading || (predictions == null && error == null) -> {
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

                        error != null -> {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        predictions != null -> {
                            predictions.predictions.forEachIndexed { index, prediction ->
                                if (index > 0) {
                                    HorizontalDivider()
                                }
                                PredictionRow(
                                    prediction = prediction,
                                    ordinalCutpoints = predictions.ordinalCutpoints,
                                )
                            }
                        }

                        else -> {
                            Text(
                                text = stringResource(R.string.prediction_unavailable),
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
private fun PredictionSummaryLine(predictions: CanyonDebitPredictions?, isLoading: Boolean, error: String?, expanded: Boolean) {
    val todayPrediction = predictions?.predictions?.firstOrNull { it.horizonDays == 0 }
        ?: predictions?.predictions?.firstOrNull()
    val tomorrowPrediction = predictions?.predictions?.firstOrNull { it.horizonDays == 1 }
    if (predictions != null && !expanded) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            todayPrediction?.let { prediction ->
                PredictionCompactTile(
                    label = stringResource(R.string.prediction_today),
                    value = predictionSummaryValue(prediction),
                    color = predictionSummaryColor(prediction),
                    modifier = Modifier.weight(1f),
                )
            }
            tomorrowPrediction?.let { prediction ->
                PredictionCompactTile(
                    label = stringResource(R.string.prediction_tomorrow),
                    value = predictionSummaryValue(prediction),
                    color = predictionSummaryColor(prediction),
                    modifier = Modifier.weight(1f),
                )
            }
            if (todayPrediction != null && tomorrowPrediction == null) {
                Box(modifier = Modifier.weight(1f))
            }
        }
        return
    }

    val todayOrdinalLevel = todayPrediction?.ordinalLevel
    val text = when {
        isLoading && predictions == null -> stringResource(R.string.prediction_loading)
        todayOrdinalLevel != null -> "${stringResource(R.string.prediction_today)} : ${ordinalLevelTitle(todayOrdinalLevel)}"
        todayPrediction != null -> "${stringResource(R.string.prediction_today)} : ${levelLabel(todayPrediction.level)}"
        error != null -> error.ifBlank { stringResource(R.string.prediction_unavailable) }
        else -> stringResource(R.string.prediction_unavailable)
    }
    val color = when {
        error != null && !isLoading && predictions == null -> MaterialTheme.colorScheme.error
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun predictionSummaryValue(prediction: DailyDebitPrediction): String =
    prediction.ordinalLevel?.let { ordinalLevelTitle(it) } ?: levelLabel(prediction.level)

@Composable
private fun predictionSummaryColor(prediction: DailyDebitPrediction): Color =
    prediction.ordinalLevel?.let { ordinalColor(it) } ?: levelColor(prediction.level)

@Composable
private fun PredictionRow(prediction: DailyDebitPrediction, ordinalCutpoints: List<Double>, modifier: Modifier = Modifier) {
    val ordinalLevel = prediction.ordinalLevel
    val ordinalScore = prediction.ordinalScore

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = when (prediction.horizonDays) {
                        0 -> stringResource(R.string.prediction_today)
                        1 -> stringResource(R.string.prediction_tomorrow)
                        else -> stringResource(R.string.prediction_day_after_tomorrow)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            Text(
                text = ordinalLevel?.let { ordinalLevelTitle(it) }
                    ?: levelLabel(prediction.level),
                style = MaterialTheme.typography.titleSmall,
                color = ordinalLevel?.let { ordinalColor(it) }
                    ?: Color.White,
                textAlign = TextAlign.End,
                modifier = when {
                    ordinalLevel != null -> Modifier.width(140.dp)

                    else ->
                        Modifier
                            .background(levelColor(prediction.level), RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                },
            )
        }

        if (ordinalLevel != null && ordinalScore != null) {
            DebitOrdinalGauge(
                score = ordinalScore,
                ordinalCutpoints = ordinalCutpoints,
            )
            HighFlowRiskLine(prediction)
            prediction.ordinalStandardDeviation?.let { standardDeviation ->
                ConfidenceBadge(
                    confidence = predictionConfidence(
                        standardDeviation = standardDeviation,
                        score = ordinalScore,
                        ordinalCutpoints = ordinalCutpoints,
                    ),
                )
            }
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
private fun ConfidenceBadge(confidence: PredictionConfidence, modifier: Modifier = Modifier) {
    val color = confidenceColor(confidence)
    Row(
        modifier = modifier
            .widthIn(max = 220.dp)
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .border(1.dp, color.copy(alpha = 0.56f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(color),
        )
        Text(
            text = confidenceLabel(confidence),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun DebitOrdinalGauge(score: Double, ordinalCutpoints: List<Double>, modifier: Modifier = Modifier) {
    val trackShape = RoundedCornerShape(999.dp)
    val markerWidth = 6.dp
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(14.dp)
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
                    .height(26.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .border(1.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(999.dp)),
            )
        }
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

internal fun predictionConfidence(
    standardDeviation: Double,
    score: Double,
    ordinalCutpoints: List<Double> = emptyList(),
): PredictionConfidence {
    val boundaryProximity = scoreBoundaryProximity(score, ordinalCutpoints)
    return when {
        standardDeviation >= 1.20 || boundaryProximity?.let { it < 0.10 } == true -> PredictionConfidence.LOW
        standardDeviation >= 0.75 || boundaryProximity?.let { it < 0.20 } == true -> PredictionConfidence.MEDIUM
        else -> PredictionConfidence.HIGH
    }
}

private fun scoreBoundaryProximity(score: Double, ordinalCutpoints: List<Double>): Double? {
    if (ordinalCutpoints.size != ORDINAL_GAUGE_LEVELS.size - 1 || !ordinalCutpoints.zipWithNext().all { (left, right) -> left < right }) {
        return null
    }
    val bounds = listOf(0.0) + ordinalCutpoints + listOf(5.0)
    val clampedScore = score.coerceIn(bounds.first(), bounds.last())
    val index = ordinalCutpoints.indexOfFirst { clampedScore < it }.takeIf { it >= 0 } ?: (ORDINAL_GAUGE_LEVELS.size - 1)
    val lower = bounds[index]
    val upper = bounds[index + 1]
    if (upper <= lower) return null
    val localFraction = ((clampedScore - lower) / (upper - lower)).coerceIn(0.0, 1.0)
    return minOf(localFraction, 1.0 - localFraction)
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

internal enum class PredictionConfidence {
    HIGH,
    MEDIUM,
    LOW,
}
