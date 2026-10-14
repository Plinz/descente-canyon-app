package fr.descentecanyon.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.HomeFeedType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun HomeFeedBanner(
    selectedFeed: HomeFeedType,
    notice: HomeFeedNotice?,
    lastSyncedAtEpochMs: Long?,
    modifier: Modifier = Modifier,
) {
    val title = when (notice) {
        HomeFeedNotice.OFFLINE_BANNER -> stringResource(R.string.home_offline_banner_title)
        HomeFeedNotice.STALE_BANNER -> stringResource(R.string.home_stale_banner_title)
        else -> return
    }
    val body = when (notice) {
        HomeFeedNotice.OFFLINE_BANNER -> if (selectedFeed == HomeFeedType.DEBITS) {
            stringResource(R.string.home_offline_banner_debits_body)
        } else {
            stringResource(R.string.home_offline_banner_forum_body)
        }

        HomeFeedNotice.STALE_BANNER -> if (selectedFeed == HomeFeedType.DEBITS) {
            stringResource(R.string.home_stale_banner_debits_body)
        } else {
            stringResource(R.string.home_stale_banner_forum_body)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (notice == HomeFeedNotice.OFFLINE_BANNER) Icons.Default.WifiOff else Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                lastSyncedAtEpochMs?.let { syncedAt ->
                    Text(
                        text = stringResource(R.string.home_last_sync, formatHomeSyncTimestamp(syncedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun HomeEmptyState(
    selectedFeed: HomeFeedType,
    notice: HomeFeedNotice?,
    onRetry: () -> Unit,
    onShowDebits: () -> Unit,
    onShowForum: () -> Unit,
    onQuickSearchClick: () -> Unit,
    onMapClick: () -> Unit,
    hasCachedDebits: Boolean,
    hasCachedForum: Boolean,
    isDebitFilterActive: Boolean,
    onClearDebitFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (selectedFeed) {
        HomeFeedType.DEBITS -> when (notice) {
            HomeFeedNotice.OFFLINE_EMPTY -> HomeStatusCard(
                icon = Icons.Default.WifiOff,
                title = stringResource(R.string.home_offline_title),
                body = stringResource(R.string.home_offline_body),
                primaryActionLabel = if (hasCachedForum) {
                    stringResource(
                        R.string.home_show_forum,
                    )
                } else {
                    stringResource(R.string.quick_search_title)
                },
                onPrimaryAction = if (hasCachedForum) onShowForum else onQuickSearchClick,
                secondaryActionLabel = if (hasCachedForum) stringResource(R.string.retry) else stringResource(R.string.home_open_map),
                onSecondaryAction = if (hasCachedForum) onRetry else onMapClick,
                modifier = modifier,
            )

            HomeFeedNotice.SERVICE_UNAVAILABLE -> HomeStatusCard(
                icon = Icons.Default.CloudOff,
                title = stringResource(R.string.home_service_unavailable_title),
                body = stringResource(R.string.home_service_unavailable_body),
                primaryActionLabel = stringResource(R.string.retry),
                onPrimaryAction = onRetry,
                secondaryActionLabel = hasCachedForum.takeIf { it }?.let { stringResource(R.string.home_show_forum) },
                onSecondaryAction = hasCachedForum.takeIf { it }?.let { onShowForum },
                modifier = modifier,
            )

            null -> if (isDebitFilterActive) {
                HomeStatusCard(
                    icon = Icons.Default.FilterList,
                    title = stringResource(R.string.home_debit_filter_no_results_title),
                    body = stringResource(R.string.home_debit_filter_no_results_body),
                    primaryActionLabel = stringResource(R.string.search_clear_filters),
                    onPrimaryAction = onClearDebitFilters,
                    modifier = modifier,
                )
            }

            else -> Unit
        }

        HomeFeedType.FORUM -> when (notice) {
            HomeFeedNotice.OFFLINE_EMPTY -> HomeStatusCard(
                icon = Icons.Default.WifiOff,
                title = stringResource(R.string.home_forum_offline_title),
                body = stringResource(R.string.home_forum_offline_body),
                primaryActionLabel = if (hasCachedDebits) {
                    stringResource(
                        R.string.home_show_debits,
                    )
                } else {
                    stringResource(R.string.quick_search_title)
                },
                onPrimaryAction = if (hasCachedDebits) onShowDebits else onQuickSearchClick,
                secondaryActionLabel = stringResource(R.string.home_open_map),
                onSecondaryAction = onMapClick,
                modifier = modifier,
            )

            HomeFeedNotice.SERVICE_UNAVAILABLE -> HomeStatusCard(
                icon = Icons.Default.CloudOff,
                title = stringResource(R.string.home_forum_service_unavailable_title),
                body = stringResource(R.string.home_forum_service_unavailable_body),
                primaryActionLabel = stringResource(R.string.retry),
                onPrimaryAction = onRetry,
                secondaryActionLabel = hasCachedDebits.takeIf { it }?.let { stringResource(R.string.home_show_debits) },
                onSecondaryAction = hasCachedDebits.takeIf { it }?.let { onShowDebits },
                modifier = modifier,
            )

            else -> Unit
        }
    }
}

@Composable
private fun HomeStatusCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onPrimaryAction) {
                Text(primaryActionLabel)
            }
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                TextButton(onClick = onSecondaryAction) {
                    Text(secondaryActionLabel)
                }
            }
        }
    }
}

private fun formatHomeSyncTimestamp(epochMs: Long): String {
    val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    return Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}
