package fr.descentecanyon.app.ui.users

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.ForumUser
import fr.descentecanyon.app.ui.common.openUrl
import fr.descentecanyon.app.ui.common.rememberFollowWithNotificationPermission
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcEmptyState
import fr.descentecanyon.app.ui.design.DcMetricTile
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.design.rememberDcScreenHorizontalPadding
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun UserSearchScreen(
    onBackClick: () -> Unit,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: UserSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toggleFollowAskingPermission = rememberFollowWithNotificationPermission()
    Scaffold(
        containerColor = LocalDcColors.current.backgroundBase,
        topBar = {
            CompactAppBar(title = stringResource(R.string.users_title), navigation = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            })
        },
        modifier = modifier,
    ) { padding ->
        val contentWidth = rememberDcContentWidth()
        val horizontalPadding = rememberDcScreenHorizontalPadding()
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).width(contentWidth).padding(horizontal = horizontalPadding),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 20.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::updateQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text(stringResource(R.string.users_search_label)) },
                )
            }
            if (state.query.trim().length < 2) {
                item {
                    DcEmptyState(
                        title = stringResource(R.string.users_search_hint_title),
                        body = stringResource(R.string.users_search_hint_body),
                        icon = Icons.Default.Person,
                    )
                }
            } else if (state.users.isEmpty()) {
                // "No user" only once the text typed was searched, not while typing.
                if (!state.isSearching) {
                    item {
                        DcEmptyState(
                            title = stringResource(R.string.users_search_empty_title),
                            body = stringResource(R.string.users_search_empty_body),
                            icon = Icons.Default.Search,
                        )
                    }
                }
            } else {
                items(state.users, key = { it.username }) { user ->
                    val isFollowed = state.isFollowed(user)
                    UserResultCard(
                        user = user,
                        isFollowed = isFollowed,
                        onClick = { onUserClick(user.username) },
                        onToggleFollow = { toggleFollowAskingPermission(isFollowed) { viewModel.toggleFollow(user) } },
                    )
                }
            }
        }
    }
}

@Composable
fun UserProfileScreen(
    onBackClick: () -> Unit,
    onOpenCanyon: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: UserProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val user = state.user
    val toggleFollowAskingPermission = rememberFollowWithNotificationPermission()
    Scaffold(
        containerColor = LocalDcColors.current.backgroundBase,
        topBar = {
            CompactAppBar(title = state.username, navigation = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            })
        },
        modifier = modifier,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (user != null) {
                UserProfileContent(
                    user = user,
                    isFollowed = state.isFollowed,
                    onToggleFollow = { toggleFollowAskingPermission(state.isFollowed, viewModel::toggleFollow) },
                    onOpenCanyon = onOpenCanyon,
                    contentPadding = contentPadding,
                )
            } else if (state.isLoaded) {
                DcEmptyState(
                    title = stringResource(R.string.user_profile_not_found_title),
                    body = stringResource(R.string.user_profile_not_found_body),
                    icon = Icons.Default.Person,
                )
            }
        }
    }
}

@Composable
private fun UserProfileContent(
    user: ForumUser,
    isFollowed: Boolean,
    onToggleFollow: () -> Unit,
    onOpenCanyon: (Int) -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalLocale.current.platformLocale)
    LazyColumn(
        modifier = Modifier.width(rememberDcContentWidth()).padding(horizontal = rememberDcScreenHorizontalPadding()),
        contentPadding = PaddingValues(top = 12.dp, bottom = contentPadding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            DcCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Person, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(user.username, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.user_profile_member_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalDcColors.current.textSecondary,
                        )
                    }
                    Button(onClick = onToggleFollow) {
                        Icon(Icons.Default.Notifications, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (isFollowed) R.string.user_following else R.string.user_follow))
                    }
                }
                Text(
                    stringResource(R.string.user_follow_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalDcColors.current.textSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        // The site's own counters, as shown under the member's reports.
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DcMetricTile(stringResource(R.string.user_metric_debits), user.debitReportCount.toString(), Modifier.weight(1f))
                DcMetricTile(stringResource(R.string.user_metric_posts), user.forumPostCount.toString(), Modifier.weight(1f))
            }
        }
        if (user.photoCount != null && user.canyonSheetContributionCount != null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DcMetricTile(stringResource(R.string.user_metric_photos), user.photoCount.toString(), Modifier.weight(1f))
                    DcMetricTile(
                        stringResource(R.string.user_metric_sheets),
                        user.canyonSheetContributionCount.toString(),
                        Modifier.weight(1f),
                    )
                }
            }
        }
        if (user.lastDebitReport != null || user.lastForumPostDate != null) {
            item { Text(stringResource(R.string.user_recent_activity), fontWeight = FontWeight.Bold) }
        }
        user.lastDebitReport?.let { report ->
            item {
                ActivitySummaryCard(
                    label = stringResource(R.string.user_last_debit),
                    detail = listOfNotNull(report.canyonName, dateFormatter.format(report.date)).joinToString(" • "),
                    onClick = report.canyonId?.let { canyonId -> { onOpenCanyon(canyonId) } },
                )
            }
        }
        user.lastForumPostDate?.let { date ->
            item {
                ActivitySummaryCard(
                    label = stringResource(R.string.user_last_forum_post),
                    detail = dateFormatter.format(date),
                    onClick = user.lastForumPostUrl?.let { url -> { context.openUrl(url) } },
                )
            }
        }
        user.forumPostsUrl?.let { url ->
            item {
                TextButton(onClick = { context.openUrl(url) }) { Text(stringResource(R.string.user_open_forum_posts)) }
            }
        }
    }
}

@Composable
internal fun UserResultCard(user: ForumUser, isFollowed: Boolean, onClick: () -> Unit, onToggleFollow: () -> Unit) {
    DcCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(user.username, fontWeight = FontWeight.SemiBold)
                val activity = listOfNotNull(
                    if (user.debitReportCount > 0) stringResource(R.string.user_activity_debits_count, user.debitReportCount) else null,
                    if (user.forumPostCount > 0) stringResource(R.string.user_activity_forum_count, user.forumPostCount) else null,
                )
                if (activity.isNotEmpty()) Text(activity.joinToString(" • "))
            }
            TextButton(onClick = onToggleFollow) {
                Text(stringResource(if (isFollowed) R.string.user_following else R.string.user_follow))
            }
        }
    }
}

@Composable
private fun ActivitySummaryCard(label: String, detail: String, onClick: (() -> Unit)?) {
    DcCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(detail)
    }
}
