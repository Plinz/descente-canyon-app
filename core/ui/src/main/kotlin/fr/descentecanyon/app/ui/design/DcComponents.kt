package fr.descentecanyon.app.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.domain.model.NiveauDebit

enum class DcCardVariant { Surface, Elevated, Photo, Warning, Condition }
enum class DcRiskLevel { Info, Low, Medium, High, Extreme, Unknown }

@Composable
fun DcCard(
    modifier: Modifier = Modifier,
    variant: DcCardVariant = DcCardVariant.Surface,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    val colors = LocalDcColors.current
    val shapes = LocalDcShapes.current
    val elevations = LocalDcElevation.current
    val containerColor = when (variant) {
        DcCardVariant.Surface -> colors.surfaceBase
        DcCardVariant.Elevated -> colors.surfaceRaised
        DcCardVariant.Photo -> colors.surfaceOverlay
        DcCardVariant.Warning -> colors.warning.copy(alpha = 0.16f)
        DcCardVariant.Condition -> colors.surfaceRaised
    }
    val borderColor = when (variant) {
        DcCardVariant.Warning -> colors.warning.copy(alpha = 0.58f)
        DcCardVariant.Condition -> colors.borderStrong.copy(alpha = 0.36f)
        else -> colors.borderSubtle
    }

    val cardContent: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shapes.lg,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            elevation = CardDefaults.cardElevation(
                defaultElevation = if (variant ==
                    DcCardVariant.Elevated
                ) {
                    elevations.raised
                } else {
                    elevations.flat
                },
            ),
            border = BorderStroke(1.dp, borderColor),
        ) { cardContent() }
    } else {
        Card(
            modifier = modifier,
            shape = shapes.lg,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            elevation = CardDefaults.cardElevation(
                defaultElevation = if (variant ==
                    DcCardVariant.Elevated
                ) {
                    elevations.raised
                } else {
                    elevations.flat
                },
            ),
            border = BorderStroke(1.dp, borderColor),
        ) { cardContent() }
    }
}

@Composable
fun DcTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalDcColors.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        color = colors.surfaceOverlay,
        contentColor = colors.textPrimary,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            colors.waterDeep.copy(alpha = 0.42f),
                            colors.surfaceRaised,
                            colors.rock.copy(alpha = 0.22f),
                        ),
                    ),
                )
                .height(52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigation != null) {
                Surface(
                    modifier = Modifier.excludeFromBackGesture(),
                    color = colors.surfaceRaised.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, colors.borderSubtle),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { navigation() }
                }
            }
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (navigation == null) 12.dp else 10.dp),
            )
            Row(modifier = Modifier.excludeFromBackGesture(), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

@Composable
fun DcFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 64.dp,
    iconSize: Dp = 28.dp,
    icon: @Composable (Modifier) -> Unit,
) {
    val colors = LocalDcColors.current
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(buttonSize),
        containerColor = colors.primaryAction,
        contentColor = colors.primaryActionContent,
        shape = LocalDcShapes.current.xl,
    ) {
        Box(modifier = Modifier.size(iconSize), contentAlignment = Alignment.Center) {
            icon(Modifier.size(iconSize))
        }
    }
}

@Composable
fun DcMetricTile(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null, icon: ImageVector? = null) {
    val colors = LocalDcColors.current
    DcCard(modifier = modifier, variant = DcCardVariant.Condition, contentPadding = 12.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, contentDescription = null, tint = colors.water, modifier = Modifier.size(18.dp))
            Column {
                Text(text = label.uppercase(), style = LocalDcTypography.current.metricLabel, color = colors.textMuted)
                Text(text = value + (unit?.let { " $it" } ?: ""), style = LocalDcTypography.current.metricValue, color = colors.textPrimary)
            }
        }
    }
}

@Composable
fun DcFlowBadge(niveau: NiveauDebit, modifier: Modifier = Modifier, compact: Boolean = true, label: String) {
    val colors = LocalDcColors.current
    val color = when (niveau) {
        NiveauDebit.SEC -> colors.flowDry
        NiveauDebit.FILET -> colors.flowTrickle
        NiveauDebit.CORRECT -> colors.flowGood
        NiveauDebit.GROS -> colors.flowHigh
        NiveauDebit.TRES_GROS -> colors.flowVeryHigh
        NiveauDebit.CRUE -> colors.flowFlood
        NiveauDebit.INCONNU -> colors.flowUnknown
    }
    val isFlood = niveau == NiveauDebit.CRUE
    Surface(
        modifier = modifier,
        shape = LocalDcShapes.current.pill,
        color = if (isFlood) color else color.copy(alpha = 0.16f),
        contentColor = if (isFlood) Color.White else color,
        border = BorderStroke(1.dp, if (isFlood) Color.White.copy(alpha = 0.55f) else color.copy(alpha = 0.42f)),
    ) {
        Text(
            text = label,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 4.dp else 6.dp),
        )
    }
}

@Composable
fun DcRiskBadge(text: String, level: DcRiskLevel, modifier: Modifier = Modifier) {
    val colors = LocalDcColors.current
    val color = when (level) {
        DcRiskLevel.Info -> colors.water
        DcRiskLevel.Low -> colors.riskLow
        DcRiskLevel.Medium -> colors.riskMedium
        DcRiskLevel.High -> colors.riskHigh
        DcRiskLevel.Extreme -> colors.riskExtreme
        DcRiskLevel.Unknown -> colors.offline
    }
    Surface(
        modifier = modifier,
        shape = LocalDcShapes.current.pill,
        color = color.copy(alpha = 0.16f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.44f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun DcSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = LocalDcColors.current.textPrimary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalDcColors.current.textMuted)
        }
        if (action != null) Row(content = action)
    }
}

@Composable
fun DcOutdoorActionCard(title: String, hint: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDcColors.current
    DcCard(onClick = onClick, modifier = modifier, variant = DcCardVariant.Elevated, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Brush.linearGradient(listOf(colors.waterDeep, colors.water)), LocalDcShapes.current.md),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = colors.snow)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
        }
    }
}

@Composable
fun DcEmptyState(title: String, modifier: Modifier = Modifier, body: String? = null, icon: ImageVector? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = LocalDcColors.current.textMuted, modifier = Modifier.size(52.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body !=
            null
        ) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalDcColors.current.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
