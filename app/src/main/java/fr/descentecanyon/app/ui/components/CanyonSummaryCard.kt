package fr.descentecanyon.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcCardVariant
import fr.descentecanyon.app.ui.design.DcFlowBadge
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.LocalDcShapes
import fr.descentecanyon.app.ui.design.LocalDcSpacing
import fr.descentecanyon.app.ui.test.TestTags
import fr.descentecanyon.app.ui.theme.CotationDifficile
import fr.descentecanyon.app.ui.theme.CotationFacile
import fr.descentecanyon.app.ui.theme.CotationMoyen
import fr.descentecanyon.app.ui.theme.CotationTresDifficile

enum class CanyonSummaryCardVariant { Compact, Rich, MapSheet }

@Composable
fun CanyonSummaryCard(
    canyon: CanyonSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: CanyonSummaryCardVariant = CanyonSummaryCardVariant.Compact,
    isFavorite: Boolean = false,
    onFavoriteClick: (() -> Unit)? = null,
    onFolderClick: (() -> Unit)? = null,
) {
    val colors = LocalDcColors.current
    val spacing = LocalDcSpacing.current
    DcCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.canyonCard(canyon.id)),
        variant = if (variant == CanyonSummaryCardVariant.Compact) DcCardVariant.Surface else DcCardVariant.Elevated,
        contentPadding = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (variant == CanyonSummaryCardVariant.MapSheet) 5.dp else 4.dp)
                    .padding(horizontal = spacing.lg),
            )
            if (variant == CanyonSummaryCardVariant.MapSheet && onFavoriteClick != null) {
                MapSheetFavoriteContent(
                    canyon = canyon,
                    isFavorite = isFavorite,
                    onFavoriteClick = onFavoriteClick,
                )
            } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = spacing.lg,
                        top = if (variant == CanyonSummaryCardVariant.Compact) spacing.md else spacing.lg,
                        end = spacing.lg,
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = canyon.nom,
                        style = if (variant == CanyonSummaryCardVariant.MapSheet) {
                            MaterialTheme.typography.titleLarge
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.textPrimary,
                        maxLines = if (variant == CanyonSummaryCardVariant.Compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val location = buildString {
                        append(canyon.pays)
                        canyon.departement?.let { append(" - $it") }
                    }
                    Row(
                        modifier = Modifier.padding(top = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = colors.textMuted,
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = location,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(spacing.sm))
                Column(horizontalAlignment = Alignment.End) {
                    if (canyon.isForbidden) {
                        ForbiddenBadge()
                    } else {
                        CotationBadge(cotation = canyon.cotation)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = spacing.lg,
                        top = if (variant == CanyonSummaryCardVariant.MapSheet) 0.dp else spacing.md,
                        end = spacing.lg,
                        bottom = if (variant == CanyonSummaryCardVariant.MapSheet) 0.dp else spacing.md,
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                canyon.interet?.let { interest ->
                    InterestStars(interest = interest)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onFolderClick != null) {
                        IconButton(
                            onClick = onFolderClick,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = stringResource(R.string.favorite_folder_manage),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    if (onFavoriteClick != null) {
                        IconButton(
                            onClick = onFavoriteClick,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                modifier = Modifier.size(24.dp),
                                contentDescription = stringResource(
                                    if (isFavorite) R.string.remove_favorite else R.string.add_favorite,
                                ),
                                tint = if (isFavorite) MaterialTheme.colorScheme.error else colors.textSecondary,
                            )
                        }
                    }
                    canyon.dernierDebit?.let { niveau ->
                        DebitBadge(niveau = niveau)
                    }
                    if (canyon.isOffline) {
                        Spacer(modifier = Modifier.width(spacing.sm))
                        SummaryIconBadge(
                            icon = Icons.Default.CloudDownload,
                            contentDescription = stringResource(R.string.offline_available),
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun MapSheetFavoriteContent(
    canyon: CanyonSummary,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
) {
    val colors = LocalDcColors.current
    val spacing = LocalDcSpacing.current
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = spacing.lg,
                    top = spacing.lg,
                    end = spacing.lg + 96.dp,
                ),
        ) {
            Text(
                text = canyon.nom,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val location = buildString {
                append(canyon.pays)
                canyon.departement?.let { append(" - $it") }
            }
            Row(
                modifier = Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Place,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.textMuted,
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = location,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                canyon.interet?.let { interest ->
                    InterestStars(interest = interest)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    canyon.dernierDebit?.let { niveau ->
                        DebitBadge(niveau = niveau)
                    }
                    if (canyon.isOffline) {
                        Spacer(modifier = Modifier.width(spacing.sm))
                        SummaryIconBadge(
                            icon = Icons.Default.CloudDownload,
                            contentDescription = stringResource(R.string.offline_available),
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .matchParentSize()
                .padding(top = spacing.lg, end = spacing.lg),
            horizontalAlignment = Alignment.End,
        ) {
            if (canyon.isForbidden) {
                ForbiddenBadge()
            } else {
                CotationBadge(cotation = canyon.cotation)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                IconButton(
                    onClick = onFavoriteClick,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        modifier = Modifier.size(28.dp),
                        contentDescription = stringResource(
                            if (isFavorite) R.string.remove_favorite else R.string.add_favorite,
                        ),
                        tint = if (isFavorite) MaterialTheme.colorScheme.error else colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryIconBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = LocalDcShapes.current.pill,
        colors = CardDefaults.cardColors(containerColor = LocalDcColors.current.water.copy(alpha = 0.14f)),
        border = BorderStroke(1.dp, LocalDcColors.current.water.copy(alpha = 0.36f)),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier
                .padding(horizontal = 7.dp, vertical = 4.dp)
                .size(16.dp),
            tint = LocalDcColors.current.water,
        )
    }
}

@Composable
fun CotationBadge(
    cotation: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val color = when {
        cotation.startsWith("v1") || cotation.startsWith("V1") -> CotationFacile
        cotation.startsWith("v2") || cotation.startsWith("V2") -> CotationFacile
        cotation.startsWith("v3") || cotation.startsWith("V3") -> CotationMoyen
        cotation.startsWith("v4") || cotation.startsWith("V4") -> CotationDifficile
        cotation.startsWith("v5") || cotation.startsWith("V5") -> CotationTresDifficile
        cotation.startsWith("v6") || cotation.startsWith("V6") -> CotationTresDifficile
        else -> MaterialTheme.colorScheme.outline
    }
    val textStyle = if (large) {
        MaterialTheme.typography.titleLarge
    } else {
        MaterialTheme.typography.labelLarge
    }
    SummaryBadge(
        text = cotation,
        color = color,
        textStyle = textStyle,
        modifier = modifier,
    )
}

@Composable
fun ForbiddenBadge(
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val textStyle = if (large) {
        MaterialTheme.typography.titleLarge
    } else {
        MaterialTheme.typography.labelLarge
    }
    SummaryBadge(
        text = stringResource(R.string.canyon_badge_forbidden),
        color = MaterialTheme.colorScheme.error,
        textStyle = textStyle,
        modifier = modifier,
    )
}

@Composable
private fun SummaryBadge(
    text: String,
    color: Color,
    textStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = LocalDcShapes.current.pill,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.34f)),
    ) {
        Text(
            text = text,
            style = textStyle,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun InterestStars(
    interest: Float,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        val clampedInterest = interest.coerceIn(0f, 4f)
        val fullStars = clampedInterest.toInt()
        val hasHalf = (clampedInterest - fullStars) >= 0.5f
        val emptyStars = 4 - fullStars - if (hasHalf) 1 else 0
        repeat(fullStars) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
        if (hasHalf) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.StarHalf,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
        repeat(emptyStars) {
            Icon(
                imageVector = Icons.Default.StarBorder,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = String.format(Locale.US, "%.1f/4", clampedInterest),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun DebitBadge(
    niveau: NiveauDebit,
    modifier: Modifier = Modifier,
) {
    DcFlowBadge(
        niveau = niveau,
        label = debitLevelLabel(niveau),
        modifier = modifier,
    )
}
