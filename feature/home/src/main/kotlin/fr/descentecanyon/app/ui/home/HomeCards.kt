package fr.descentecanyon.app.ui.home

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.ForumActiveTopic
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.ui.common.openUrl
import fr.descentecanyon.app.ui.components.DebitBadge
import fr.descentecanyon.app.ui.components.debitLevelColor
import fr.descentecanyon.app.ui.design.DcDropdownMenu
import fr.descentecanyon.app.ui.design.DcDropdownMenuItem
import fr.descentecanyon.app.ui.test.TestTags
import java.time.format.DateTimeFormatter

internal fun descenteCanyonCanyonUrl(canyonId: Int): String = "https://www.descente-canyon.com/canyoning/canyon/$canyonId/"

/** Looks like the search field it opens: one line, the reports stay in view below. */
@Composable
internal fun QuickSearchBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.quick_search_title)
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .semantics { contentDescription = label }
            .testTag(TestTags.HOME_QUICK_SEARCH),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = stringResource(R.string.search_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Secondary entries of the home screen, side by side so they do not push the reports down. */
@Composable
internal fun HomeShortcutRow(onUsersClick: () -> Unit, onDonateClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HomeShortcut(
            title = stringResource(R.string.users_title),
            icon = Icons.Default.People,
            onClick = onUsersClick,
            modifier = Modifier.weight(1f),
        )
        HomeShortcut(
            title = stringResource(R.string.support_donation_title),
            icon = Icons.Default.Favorite,
            onClick = onDonateClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HomeShortcut(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun CreditCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Text(
            text = stringResource(R.string.credit_source),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
internal fun DebitCard(debit: Debit, isExternalOnly: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, place: String? = null) {
    val bgColor = debitLevelColor(debit.niveau)
    val isCrue = debit.niveau == NiveauDebit.CRUE

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isCrue) 8.dp else 6.dp)
                    .background(if (isCrue) bgColor else bgColor.copy(alpha = 0.9f)),
            )
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = debit.canyonNom?.takeIf {
                            it.isNotBlank()
                        } ?: stringResource(R.string.canyon_fallback_title, debit.canyonId),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    DebitBadge(niveau = debit.niveau)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Where the canyon is: the feed mixes every country of the site.
                    Text(
                        text = listOfNotNull(debit.date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), place).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    debit.auteur?.let { auteur ->
                        Text(
                            text = auteur,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                debit.commentaire?.takeIf { it.isNotBlank() }?.let { comment ->
                    Text(
                        text = comment,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (isExternalOnly) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.home_debit_external_only),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ForumTopicCard(
    topic: ForumActiveTopic,
    isCategoryFollowed: Boolean,
    isThreadFollowed: Boolean,
    onClick: () -> Unit,
    onToggleCategoryFollow: () -> Unit,
    onToggleThreadFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.ChatBubbleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = topic.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ForumMetaChip(
                            text = topic.forumName,
                            icon = Icons.Default.FolderSpecial,
                            modifier = Modifier.weight(1f),
                        )
                        ForumMetaChip(
                            text = "${topic.replyCount}",
                            icon = Icons.Default.ChatBubbleOutline,
                            modifier = Modifier.widthIn(min = 48.dp),
                        )
                        if (isThreadFollowed || isCategoryFollowed) {
                            ForumMetaChip(
                                text = if (isThreadFollowed) {
                                    stringResource(R.string.notifications_forum_thread_badge)
                                } else {
                                    stringResource(R.string.notifications_forum_category_badge)
                                },
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.notifications_forum_actions),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DcDropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DcDropdownMenuItem(
                            text = if (isThreadFollowed) {
                                stringResource(R.string.notifications_forum_thread_unfollow_action)
                            } else {
                                stringResource(R.string.notifications_forum_thread_follow_action)
                            },
                            icon = if (isThreadFollowed) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
                            onClick = {
                                menuExpanded = false
                                onToggleThreadFollow()
                            },
                        )
                        DcDropdownMenuItem(
                            text = if (isCategoryFollowed) {
                                stringResource(R.string.notifications_forum_unfollow_action)
                            } else {
                                stringResource(R.string.notifications_forum_follow_action)
                            },
                            icon = Icons.Default.FolderSpecial,
                            onClick = {
                                menuExpanded = false
                                onToggleCategoryFollow()
                            },
                        )
                    }
                }
            }
            Text(
                text = topic.lastAuthor
                    ?.takeIf { it.isNotBlank() }
                    ?.let { author -> stringResource(R.string.home_forum_last_message_by, author, topic.lastPostedAtText) }
                    ?: topic.lastPostedAtText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ForumMetaChip(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun openExternalUrl(context: Context, url: String) {
    context.openUrl(url)
}
