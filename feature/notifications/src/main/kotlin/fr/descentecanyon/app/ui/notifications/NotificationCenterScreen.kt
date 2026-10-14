package fr.descentecanyon.app.ui.notifications

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.FollowedCanyon
import fr.descentecanyon.app.domain.model.FollowedForumCategory
import fr.descentecanyon.app.domain.model.FollowedForumThread
import fr.descentecanyon.app.domain.model.FollowedUser
import fr.descentecanyon.app.domain.model.TrackedActivityEvent
import fr.descentecanyon.app.domain.model.TrackedActivityType
import fr.descentecanyon.app.ui.common.openUrl
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.DcEmptyState
import fr.descentecanyon.app.ui.design.DcSectionHeader
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.LocalDcShapes
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.design.rememberDcScreenHorizontalPadding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NotificationCenterScreen(
    onBackClick: () -> Unit,
    onCanyonClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: NotificationCenterViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val contentWidth = rememberDcContentWidth()
    val screenHorizontalPadding = rememberDcScreenHorizontalPadding()
    var notificationsGranted by remember(context) { mutableStateOf(areNotificationsGranted(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsGranted = areNotificationsGranted(context)
    }

    Scaffold(
        containerColor = LocalDcColors.current.backgroundBase,
        topBar = {
            CompactAppBar(
                title = stringResource(R.string.notifications_screen_title),
                navigation = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(contentWidth)
                    .padding(horizontal = screenHorizontalPadding),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(modifier = Modifier.size(2.dp)) }

                if (!notificationsGranted) {
                    item {
                        NotificationPermissionCard(
                            onRequestPermission = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                        )
                    }
                }

                item {
                    SectionHeader(
                        title = stringResource(R.string.notifications_activity_title),
                        subtitle = stringResource(R.string.notifications_activity_subtitle),
                    )
                }

                item {
                    SectionHeader(
                        title = stringResource(R.string.notifications_followed_users_title),
                        subtitle = stringResource(R.string.notifications_followed_users_subtitle),
                    )
                }

                if (uiState.followedUsers.isEmpty()) {
                    item {
                        EmptyCard(
                            icon = Icons.Default.NotificationsActive,
                            title = stringResource(R.string.notifications_followed_users_empty_title),
                            body = stringResource(R.string.notifications_followed_users_empty_body),
                        )
                    }
                } else {
                    items(uiState.followedUsers, key = { it.normalizedUsername }) { user ->
                        FollowedUserCard(
                            user = user,
                            onRemove = { viewModel.removeUserFollow(user.normalizedUsername) },
                        )
                    }
                }

                if (uiState.recentEvents.isEmpty()) {
                    item {
                        EmptyCard(
                            icon = Icons.Default.NotificationsActive,
                            title = stringResource(R.string.notifications_activity_empty_title),
                            body = stringResource(R.string.notifications_activity_empty_body),
                        )
                    }
                } else {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = viewModel::clearRecentActivity) {
                                Text(stringResource(R.string.notifications_clear_activity))
                            }
                        }
                    }
                    items(uiState.recentEvents, key = { it.id }) { event ->
                        ActivityEventCard(
                            event = event,
                            onClick = {
                                when (event.type) {
                                    TrackedActivityType.DEBIT -> event.canyonId?.let(onCanyonClick)
                                    TrackedActivityType.FORUM -> event.externalUrl?.let { openExternalUrl(context, it) }
                                }
                            },
                        )
                    }
                }

                item {
                    SectionHeader(
                        title = stringResource(R.string.notifications_canyons_title),
                        subtitle = stringResource(R.string.notifications_canyons_subtitle),
                    )
                }

                if (uiState.followedCanyons.isEmpty()) {
                    item {
                        EmptyCard(
                            icon = Icons.Default.WaterDrop,
                            title = stringResource(R.string.notifications_canyons_empty_title),
                            body = stringResource(R.string.notifications_canyons_empty_body),
                        )
                    }
                } else {
                    items(uiState.followedCanyons, key = { it.canyonId }) { canyon ->
                        FollowedCanyonCard(
                            canyon = canyon,
                            onOpen = { onCanyonClick(canyon.canyonId) },
                            onRemove = { viewModel.removeCanyonFollow(canyon.canyonId) },
                        )
                    }
                }

                item {
                    SectionHeader(
                        title = stringResource(R.string.notifications_forum_title),
                        subtitle = stringResource(R.string.notifications_forum_subtitle),
                    )
                }

                if (uiState.followedForumCategories.isEmpty() && uiState.followedForumThreads.isEmpty()) {
                    item {
                        EmptyCard(
                            icon = Icons.Default.Tune,
                            title = stringResource(R.string.notifications_forum_empty_title),
                            body = stringResource(R.string.notifications_forum_empty_body),
                        )
                    }
                } else {
                    items(uiState.followedForumCategories, key = { it.key }) { forumCategory ->
                        FollowedForumCategoryCard(
                            forumCategory = forumCategory,
                            onRemove = { viewModel.removeForumCategoryFollow(forumCategory.key) },
                        )
                    }
                    items(uiState.followedForumThreads, key = { it.topicId }) { thread ->
                        FollowedForumThreadCard(
                            thread = thread,
                            onRemove = { viewModel.removeForumThreadFollow(thread.topicId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowedUserCard(user: FollowedUser, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.NotificationsActive, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(user.username, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(
                        if (user.forumUserId ==
                            null
                        ) {
                            R.string.notifications_followed_user_debits_only
                        } else {
                            R.string.notifications_followed_user_all
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRemove) { Text(stringResource(R.string.notifications_unfollow)) }
        }
    }
}

@Composable
private fun NotificationPermissionCard(onRequestPermission: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.notifications_permission_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.notifications_permission_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRequestPermission, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.notifications_permission_action))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    DcSectionHeader(title = title, subtitle = subtitle, modifier = modifier)
}

@Composable
private fun EmptyCard(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        DcEmptyState(
            title = title,
            body = body,
            icon = icon,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ActivityEventCard(event: TrackedActivityEvent, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDcColors.current
    val shapes = LocalDcShapes.current
    val accent = if (event.type == TrackedActivityType.DEBIT) colors.water else colors.rock
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = accent.copy(alpha = 0.14f),
                    contentColor = accent,
                    shape = shapes.pill,
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
                ) {
                    Text(
                        text = if (event.type == TrackedActivityType.DEBIT) {
                            stringResource(R.string.home_feed_debits)
                        } else {
                            event.forumName ?: stringResource(R.string.home_feed_forum)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    )
                }
                Text(
                    text = formatTimestamp(event.occurredAtEpochMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(10.dp)
                        .background(accent, shape = shapes.pill),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = event.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (event.type == TrackedActivityType.FORUM) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FollowedCanyonCard(canyon: FollowedCanyon, onOpen: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    FollowRowCard(
        modifier = modifier,
        title = canyon.canyonName,
        subtitle = stringResource(R.string.notifications_followed_canyon_meta, canyon.canyonId),
        leadingIcon = Icons.Default.WaterDrop,
        onOpen = onOpen,
        onRemove = onRemove,
    )
}

@Composable
private fun FollowedForumCategoryCard(forumCategory: FollowedForumCategory, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    FollowRowCard(
        modifier = modifier,
        title = forumCategory.forumName,
        subtitle = stringResource(R.string.notifications_followed_forum_meta),
        leadingIcon = Icons.Default.Tune,
        onOpen = null,
        onRemove = onRemove,
    )
}

@Composable
private fun FollowedForumThreadCard(thread: FollowedForumThread, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    FollowRowCard(
        modifier = modifier,
        title = thread.title,
        subtitle = thread.forumName,
        leadingIcon = Icons.Default.RadioButtonChecked,
        onOpen = null,
        onRemove = onRemove,
    )
}

@Composable
private fun FollowRowCard(
    title: String,
    subtitle: String,
    leadingIcon: ImageVector? = null,
    onOpen: (() -> Unit)?,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDcColors.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.let {
                Box(
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(imageVector = it, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            if (onOpen != null) {
                TextButton(onClick = onOpen) {
                    Text(stringResource(R.string.notifications_open))
                }
            }
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.notifications_unfollow))
            }
        }
    }
}

private fun areNotificationsGranted(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun openExternalUrl(context: Context, url: String) {
    context.openUrl(url)
}

private fun formatTimestamp(epochMs: Long): String = Instant.ofEpochMilli(epochMs)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
