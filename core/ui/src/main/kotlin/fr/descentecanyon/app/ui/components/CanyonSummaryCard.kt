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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
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
import java.time.LocalDate
import java.util.Locale

enum class CanyonSummaryCardVariant { Compact, Rich, MapSheet }

/** Short information at the end of the place line: the drive from a stay, the distance from the user. */
data class CanyonCardInfo(val icon: ImageVector, val text: String, val description: String)

/**
 * A canyon in a list, always three lines whatever it carries: its name and rating, where it is (and
 * [info] at the end), its interest and its badges at the end. A badge never adds a line: when they do
 * not fit, they lose their words first.
 */
@Composable
fun CanyonSummaryCard(
    canyon: CanyonSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: CanyonSummaryCardVariant = CanyonSummaryCardVariant.Compact,
    isFavorite: Boolean = false,
    onFavoriteClick: (() -> Unit)? = null,
    /** Already in the user's logbook. */
    isDescended: Boolean = false,
    info: CanyonCardInfo? = null,
    /** The other day of a stay the canyon is planned for: the first of the badges. */
    plannedOn: LocalDate? = null,
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
        if (variant == CanyonSummaryCardVariant.MapSheet && onFavoriteClick != null) {
            MapSheetFavoriteContent(
                canyon = canyon,
                isFavorite = isFavorite,
                onFavoriteClick = onFavoriteClick,
                isDescended = isDescended,
            )
            return@DcCard
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // A forbidden canyon is there for the record: it steps back.
                .alpha(if (canyon.isForbidden) FORBIDDEN_ALPHA else 1f)
                .padding(
                    start = spacing.lg,
                    top = if (variant == CanyonSummaryCardVariant.Compact) spacing.md else spacing.lg,
                    end = spacing.lg,
                    bottom = spacing.md,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(spacing.sm))
                if (canyon.isForbidden) ForbiddenBadge() else CotationBadge(cotation = canyon.cotation)
            }
            Row(modifier = Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Place,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.textMuted,
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = canyonLocation(canyon),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                info?.let { InfoLabel(it) }
            }
            val hasBadges = plannedOn != null || canyon.dernierDebit != null || isDescended
            if (canyon.interet != null || hasBadges || onFavoriteClick != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    canyon.interet?.let { InterestStars(interest = it, showScale = false) }
                    Spacer(modifier = Modifier.weight(1f).widthIn(min = spacing.sm))
                    if (hasBadges) {
                        CardBadges(canyon = canyon, plannedOn = plannedOn, isDescended = isDescended)
                    }
                    onFavoriteClick?.let { FavoriteButton(isFavorite = isFavorite, onClick = it, size = 36.dp) }
                }
            }
        }
    }
}

/** "France - Alpes-Maritimes". */
private fun canyonLocation(canyon: CanyonSummary): String = buildString {
    append(canyon.pays)
    canyon.departement?.let { append(" - $it") }
}

@Composable
private fun InfoLabel(info: CanyonCardInfo) {
    val colors = LocalDcColors.current
    Row(
        modifier = Modifier.padding(start = 8.dp).semantics(mergeDescendants = true) { contentDescription = info.description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = info.icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.textSecondary)
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = info.text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
}

/**
 * The badges at the end of the interest line, what helps to decide first: planned another day of
 * the stay, the last flow reported, then descended. Too wide, "Fait" keeps only its check, then the
 * flow its level.
 */
@Composable
private fun CardBadges(canyon: CanyonSummary, plannedOn: LocalDate?, isDescended: Boolean) {
    val level = canyon.dernierDebit
    val levelWord = level?.let { capitalized(debitRangeWord(it)) }
    val reportDay = canyon.dernierDebitDate?.let { shortRelativeDayLabel(it) }

    @Composable
    fun Badges(withDate: Boolean, fullDescended: Boolean) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            plannedOn?.let { PlannedBadge(it) }
            if (level != null && levelWord != null) {
                DcFlowBadge(niveau = level, label = if (withDate && reportDay != null) "$levelWord · $reportDay" else levelWord)
            }
            if (isDescended) DescendedBadge(compact = !fullDescended)
        }
    }
    FirstThatFits(
        { Badges(withDate = true, fullDescended = true) },
        { Badges(withDate = true, fullDescended = false) },
        { Badges(withDate = false, fullDescended = false) },
    )
}

/** "jeu. 30 oct.": the other day of the stay the canyon is planned for. */
@Composable
private fun PlannedBadge(date: LocalDate) {
    val colors = LocalDcColors.current
    val day = shortDayLabel(date)
    val description = stringResource(R.string.stay_picker_planned, day)
    Surface(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        shape = LocalDcShapes.current.pill,
        color = colors.warning.copy(alpha = 0.14f),
        contentColor = colors.warning,
        border = BorderStroke(1.dp, colors.warning.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(Icons.Default.EventAvailable, contentDescription = null, modifier = Modifier.size(13.dp))
            Text(day, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/**
 * The first of [variants] whose width fits, else the last one: a line of badges shortens instead of
 * wrapping and making the card taller.
 */
@Composable
private fun FirstThatFits(vararg variants: @Composable () -> Unit) {
    SubcomposeLayout { constraints ->
        val free = constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity)
        var chosen: Placeable? = null
        for ((index, variant) in variants.withIndex()) {
            val isLast = index == variants.lastIndex
            val placeable = subcompose(index, variant).first().measure(if (isLast) constraints.copy(minWidth = 0) else free)
            if (isLast || placeable.width <= constraints.maxWidth) {
                chosen = placeable
                break
            }
        }
        val placeable = requireNotNull(chosen)
        val width = placeable.width.coerceAtMost(constraints.maxWidth)
        layout(width, placeable.height) { placeable.placeRelative(0, 0) }
    }
}

@Composable
private fun FavoriteButton(isFavorite: Boolean, onClick: () -> Unit, size: Dp) {
    IconButton(onClick = onClick, modifier = Modifier.size(size)) {
        Icon(
            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            modifier = Modifier.size(22.dp),
            contentDescription = stringResource(if (isFavorite) R.string.remove_favorite else R.string.add_favorite),
            tint = if (isFavorite) MaterialTheme.colorScheme.error else LocalDcColors.current.textSecondary,
        )
    }
}

private const val FORBIDDEN_ALPHA = 0.6f

@Composable
private fun MapSheetFavoriteContent(canyon: CanyonSummary, isFavorite: Boolean, onFavoriteClick: () -> Unit, isDescended: Boolean) {
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
                        LastReportDate(canyon)
                        DebitBadge(niveau = niveau)
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
            if (isDescended) DescendedBadge(modifier = Modifier.padding(top = 4.dp))
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

/** The cotation; with [onClick], it opens its explanation and shows an info mark. */
@Composable
fun CotationBadge(cotation: String, modifier: Modifier = Modifier, large: Boolean = false, onClick: (() -> Unit)? = null) {
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
        onClick = onClick,
    )
}

@Composable
fun ForbiddenBadge(modifier: Modifier = Modifier, large: Boolean = false) {
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
    onClick: (() -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = text, style = textStyle, fontWeight = FontWeight.Bold, color = color)
            if (onClick != null) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            }
        }
    }
    val shape = LocalDcShapes.current.pill
    val colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))
    val border = BorderStroke(1.dp, color.copy(alpha = 0.34f))
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = colors,
            border = border,
        ) { content() }
    } else {
        Card(modifier = modifier, shape = shape, colors = colors, border = border) { content() }
    }
}

@Composable
fun InterestStars(
    interest: Float,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    emptyStarColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
    /** "2.5/4" rather than "2.5": the stars already show the scale where room is short. */
    showScale: Boolean = true,
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
                tint = emptyStarColor,
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = String.format(Locale.US, if (showScale) "%.1f/4" else "%.1f", clampedInterest),
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun DebitBadge(niveau: NiveauDebit, modifier: Modifier = Modifier) {
    DcFlowBadge(
        niveau = niveau,
        label = debitLevelLabel(niveau),
        modifier = modifier,
    )
}

@Composable
private fun LastReportDate(canyon: CanyonSummary) {
    val date = canyon.dernierDebitDate ?: return
    Text(
        text = relativeDayLabel(date),
        style = MaterialTheme.typography.labelSmall,
        color = LocalDcColors.current.textMuted,
        modifier = Modifier.padding(end = 6.dp),
    )
}
