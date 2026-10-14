package fr.descentecanyon.app.ui.canyon

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.ui.components.DebitBadge
import fr.descentecanyon.app.ui.components.debitLevelColor
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.LocalDcShapes
import java.time.format.DateTimeFormatter

@Composable
internal fun DebitListItem(
    debit: Debit,
    isLatest: Boolean = false,
    onAuthorClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(debit.id) { mutableStateOf(false) }
    val bgColor = debitLevelColor(debit.niveau)
    val colorScheme = MaterialTheme.colorScheme
    val dcColors = LocalDcColors.current
    val isCrue = debit.niveau == NiveauDebit.CRUE
    val primaryTextColor = if (isCrue) Color.White else colorScheme.onSurface
    val secondaryTextColor = if (isCrue) Color.White.copy(alpha = 0.78f) else colorScheme.onSurfaceVariant

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = when {
                isCrue -> bgColor
                isLatest -> dcColors.surfaceRaised
                else -> bgColor.copy(alpha = 0.1f)
            },
        ),
        border = when {
            isCrue -> BorderStroke(1.dp, Color.White.copy(alpha = 0.45f))
            isLatest -> BorderStroke(1.dp, dcColors.primaryAction.copy(alpha = 0.72f))
            else -> null
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLatest) {
                        SmallMetaBadge(
                            text = stringResource(R.string.debit_latest_badge),
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Text(
                        text = debit.date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = primaryTextColor,
                    )
                    debit.isDescended?.let { isDescended ->
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isDescended) {
                                stringResource(
                                    R.string.debit_type_descended,
                                )
                            } else {
                                stringResource(R.string.debit_type_not_descended)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isCrue) Color.White.copy(alpha = 0.9f) else colorScheme.primary,
                        )
                    }
                }
                debit.auteur?.let { auteur ->
                    Text(
                        text = auteur,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor,
                        modifier = if (onAuthorClick == null) Modifier else Modifier.clickable { onAuthorClick(auteur) },
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Column {
                        debit.commentaire?.takeIf { it.isNotBlank() }?.let { comment ->
                            Text(
                                text = comment,
                                style = MaterialTheme.typography.bodySmall,
                                color = primaryTextColor,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        Row(
                            modifier = Modifier.padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            debit.waterTemperature?.let {
                                Text(
                                    text = stringResource(R.string.debit_water_temperature_short, it),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = secondaryTextColor,
                                )
                            }
                            debit.airTemperature?.let {
                                Text(
                                    text = stringResource(R.string.debit_air_temperature_short, it),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = secondaryTextColor,
                                )
                            }
                        }
                    }
                }
                if (!expanded) {
                    debit.commentaire?.takeIf { it.isNotBlank() }?.let { comment ->
                        Text(
                            text = comment,
                            style = MaterialTheme.typography.bodySmall,
                            color = primaryTextColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DebitBadge(niveau = debit.niveau)
                debit.waterTemperature?.let {
                    SmallMetaBadge(text = it)
                }
            }
        }
    }
}

@Composable
private fun SmallMetaBadge(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = LocalDcShapes.current.pill,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
