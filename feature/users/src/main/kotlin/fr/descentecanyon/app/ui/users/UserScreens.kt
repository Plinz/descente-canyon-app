package fr.descentecanyon.app.ui.users

import android.content.Intent
import android.net.Uri
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
                item {
                    DcEmptyState(
                        title = stringResource(R.string.users_search_empty_title),
                        body = stringResource(R.string.users_search_empty_body),
                        icon = Icons.Default.Search,
                    )
                }
            } else {
                items(state.users, key = { it.username }) { user ->
                    val isFollowed = user.normalizedUsername in state.followedUsernames
                    UserResultCard(
                        user = user,
                        isFollowed = isFollowed,
                        onClick = { onUserClick(user.normalizedUsername) },
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
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: UserProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val user = state.user
    val context = LocalContext.current
    val toggleFollowAskingPermission = rememberFollowWithNotificationPermission()
    Scaffold(
        containerColor = LocalDcColors.current.backgroundBase,
        topBar = {
            CompactAppBar(title = user?.username ?: stringResource(R.string.user_profile_fallback_title), navigation = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            })
        },
        modifier = modifier,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (user == null) {
                DcEmptyState(
                    title = stringResource(R.string.user_profile_not_found_title),
                    body = stringResource(R.string.user_profile_not_found_body),
                    icon = Icons.Default.Person,
                )
            } else {
                val contentWidth = rememberDcContentWidth()
                val horizontalPadding = rememberDcScreenHorizontalPadding()
                LazyColumn(
                    modifier = Modifier.width(contentWidth).padding(horizontal = horizontalPadding),
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
                                        listOfNotNull(
                                            if (user.hasDebitActivity) stringResource(R.string.debits) else null,
                                            if (user.hasForumActivity) stringResource(R.string.user_activity_forum) else null,
                                        ).joinToString(" • "),
                                    )
                                }
                                // Only forum posts can be followed: the site does not list flow reports by author.
                                if (user.forumUserId != null || state.isFollowed) {
                                    Button(onClick = { toggleFollowAskingPermission(state.isFollowed, viewModel::toggleFollow) }) {
                                        Icon(Icons.Default.Notifications, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(if (state.isFollowed) R.string.user_following else R.string.user_follow))
                                    }
                                }
                            }
                            if (user.forumUserId == null) {
                                Text(
                                    stringResource(R.string.user_follow_unavailable),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LocalDcColors.current.textSecondary,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DcMetricTile(stringResource(R.string.debits), user.debitObservationCount.toString(), Modifier.weight(1f))
                            DcMetricTile(stringResource(R.string.user_metric_posts), user.forumPostCount.toString(), Modifier.weight(1f))
                        }
                    }
                    item { Text(stringResource(R.string.user_recent_activity), fontWeight = FontWeight.Bold) }
                    user.lastDebitObservationAt?.let { date ->
                        item { ActivitySummaryCard(stringResource(R.string.user_last_debit), date, user.lastDebitObservationUrl) }
                    }
                    user.lastForumPostAt?.let { date ->
                        item { ActivitySummaryCard(stringResource(R.string.user_last_forum_post), date, user.lastForumPostUrl) }
                    }
                    user.profileUrl?.let { profileUrl ->
                        item {
                            TextButton(onClick = {
                                context.openUrl(profileUrl)
                            }) { Text(stringResource(R.string.user_open_forum_profile)) }
                        }
                    }
                }
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
                Text(
                    listOfNotNull(
                        if (user.hasDebitActivity) {
                            stringResource(
                                R.string.user_activity_debits_count,
                                user.debitObservationCount,
                            )
                        } else {
                            null
                        },
                        if (user.hasForumActivity) stringResource(R.string.user_activity_forum_count, user.forumPostCount) else null,
                    ).joinToString(" • "),
                )
            }
            if (user.forumUserId != null || isFollowed) {
                TextButton(onClick = onToggleFollow) {
                    Text(stringResource(if (isFollowed) R.string.user_following else R.string.user_follow))
                }
            }
        }
    }
}

@Composable
private fun ActivitySummaryCard(label: String, date: String, url: String?) {
    val context = LocalContext.current
    DcCard(onClick = if (url == null) null else ({ context.openUrl(url) })) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(date)
    }
}
