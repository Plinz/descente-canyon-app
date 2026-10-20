package fr.descentecanyon.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Hiking
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
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.ForumCategory
import fr.descentecanyon.app.domain.model.HomeFeedType
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcCardVariant
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.test.TestTags
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
    forumFilterCategory: ForumCategory?,
    onOpenForumCategory: (ForumCategory) -> Unit,
    onClearForumFilter: () -> Unit,
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

            // Only the latest topics of the whole forum are listed: a quiet section is often empty.
            null -> if (forumFilterCategory != null) {
                HomeStatusCard(
                    icon = Icons.Default.FilterList,
                    title = stringResource(R.string.home_forum_filter_no_results_title),
                    body = stringResource(R.string.home_forum_filter_no_results_body),
                    primaryActionLabel = stringResource(R.string.home_forum_filter_open),
                    onPrimaryAction = { onOpenForumCategory(forumFilterCategory) },
                    secondaryActionLabel = stringResource(R.string.search_clear_filters),
                    onSecondaryAction = onClearForumFilter,
                    modifier = modifier,
                )
            }

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

/** Reminds that debit reports written offline are still on the device. */
@Composable
internal fun PendingDebitsBanner(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    DcCard(
        variant = DcCardVariant.Warning,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.HOME_PENDING_DEBITS_BANNER),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.CloudUpload, contentDescription = null)
            Text(
                text = pluralStringResource(R.plurals.debit_pending_count, count, count),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClick) {
                Text(stringResource(R.string.pending_debits_view))
            }
        }
    }
}

/** The announced outing, until the user says they are back; the end reminder offers to report the flow. */
@Composable
internal fun ActiveOutingCard(
    outing: PlannedOuting,
    onOpen: () -> Unit,
    onBack: () -> Unit,
    onEmergency: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val zone = ZoneId.systemDefault()
    val start = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).format(outing.start.atZone(zone))
    val back = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(outing.expectedBack.atZone(zone))
    DcCard(
        variant = DcCardVariant.Condition,
        onClick = onOpen,
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.HOME_ACTIVE_OUTING),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Hiking, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(outing.canyonName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.outing_active_summary, start, back), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.outing_end_action)) }
                TextButton(onClick = onEmergency) {
                    Text(stringResource(R.string.outing_emergency_action), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** The next outings planned, each opening its plan, and the list of all of them. */
@Composable
internal fun UpcomingOutingsCard(
    outings: List<PlannedOuting>,
    onOpen: (PlannedOuting) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val zone = ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)
    DcCard(modifier = modifier.fillMaxWidth().testTag(TestTags.HOME_UPCOMING_OUTINGS)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.EventAvailable, contentDescription = null)
                Text(
                    stringResource(R.string.home_upcoming_outings),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            outings.forEach { outing ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(outing) }.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        outing.canyonName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatter.format((outing.departure ?: outing.start).atZone(zone)),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalDcColors.current.textSecondary,
                    )
                }
            }
            TextButton(onClick = onSeeAll) { Text(stringResource(R.string.home_all_outings)) }
        }
    }
}
