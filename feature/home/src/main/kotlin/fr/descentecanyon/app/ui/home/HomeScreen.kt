package fr.descentecanyon.app.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.ForumActiveTopic
import fr.descentecanyon.app.domain.model.ForumCategory
import fr.descentecanyon.app.domain.model.HomeFeedType
import fr.descentecanyon.app.domain.model.forumCategoryKey
import fr.descentecanyon.app.ui.auth.AuthViewModel
import fr.descentecanyon.app.ui.auth.LoginDialog
import fr.descentecanyon.app.ui.common.needsNotificationPermission
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.design.rememberDcScreenHorizontalPadding
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onCanyonClick: (Int) -> Unit,
    onQuickSearchClick: () -> Unit,
    onMapClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onUsersClick: () -> Unit,
    onPendingDebitsClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    homeViewModel: HomeViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val donationUrl = stringResource(R.string.support_donation_url)
    val contentWidth = rememberDcContentWidth()
    val screenHorizontalPadding = rememberDcScreenHorizontalPadding()
    var showLoginDialog by remember { mutableStateOf(false) }
    var selectedFeedOverride by remember { mutableStateOf<HomeFeedType?>(null) }
    var pendingFollowAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        pendingFollowAction?.invoke()
        pendingFollowAction = null
    }

    // Following needs notifications: ask for the permission first, then run the pending toggle.
    fun toggleFollowAskingPermission(isFollowed: Boolean, toggle: () -> Unit) {
        if (!isFollowed && context.needsNotificationPermission()) {
            pendingFollowAction = toggle
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            toggle()
        }
    }
    val selectedFeed = selectedFeedOverride ?: homeState.selectedFeed
    val listState = rememberLazyListState()
    val selectFeed: (HomeFeedType) -> Unit = { type ->
        selectedFeedOverride = type
        homeViewModel.selectFeed(type)
    }

    LaunchedEffect(homeState.selectedFeed, selectedFeedOverride) {
        if (selectedFeedOverride == homeState.selectedFeed) {
            selectedFeedOverride = null
        }
    }

    LaunchedEffect(
        listState,
        selectedFeed,
        homeState.debitGeoFilter.canLoadMore,
        homeState.debitFeed.items.size,
    ) {
        if (selectedFeed != HomeFeedType.DEBITS || !homeState.debitGeoFilter.canLoadMore) return@LaunchedEffect
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            layoutInfo.totalItemsCount > 0 && lastVisibleIndex >= layoutInfo.totalItemsCount - LOAD_MORE_VISIBLE_THRESHOLD
        }
            .distinctUntilChanged()
            .collect { shouldLoadMore ->
                if (shouldLoadMore) homeViewModel.loadMoreDebits()
            }
    }

    if (showLoginDialog) {
        LoginDialog(
            uiState = authState,
            onUsernameChanged = authViewModel::onUsernameChanged,
            onPasswordChanged = authViewModel::onPasswordChanged,
            onLogin = authViewModel::login,
            onLogout = authViewModel::logout,
            onDismiss = { showLoginDialog = false },
        )
    }

    val activeFeedState = when (selectedFeed) {
        HomeFeedType.DEBITS -> homeState.debitFeed
        HomeFeedType.FORUM -> homeState.forumFeed
    }

    Scaffold(
        containerColor = LocalDcColors.current.backgroundBase,
        topBar = {
            CompactAppBar(
                title = stringResource(R.string.app_name),
                actions = {
                    IconButton(onClick = onNotificationsClick) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = stringResource(R.string.notifications_screen_title),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    IconButton(onClick = { showLoginDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = stringResource(R.string.user_account),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                            modifier = Modifier.size(24.dp),
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
                state = listState,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(contentWidth)
                    .padding(horizontal = screenHorizontalPadding),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }

                if (homeState.pendingDebitCount > 0) {
                    item {
                        PendingDebitsBanner(count = homeState.pendingDebitCount, onClick = onPendingDebitsClick)
                    }
                }

                item {
                    QuickSearchCard(onClick = onQuickSearchClick)
                }

                item {
                    HomeActionCard(
                        title = stringResource(R.string.users_title),
                        hint = stringResource(R.string.home_users_hint),
                        icon = Icons.Default.People,
                        onClick = onUsersClick,
                    )
                }

                item {
                    DonationCard(onClick = { openExternalUrl(context, donationUrl) })
                }

                item {
                    HomeFeedPicker(
                        selectedFeed = selectedFeed,
                        onFeedSelected = selectFeed,
                    )
                }

                item {
                    HomeFeedHeader(
                        selectedFeed = selectedFeed,
                        onRefresh = homeViewModel::refreshSelectedFeed,
                    )
                }

                if (selectedFeed == HomeFeedType.DEBITS) {
                    item {
                        HomeDebitGeoFilterControls(
                            filterState = homeState.debitGeoFilter,
                            onCountrySelected = homeViewModel::selectDebitCountry,
                            onDepartmentSelected = homeViewModel::selectDebitDepartment,
                            onClear = homeViewModel::clearDebitGeoFilter,
                        )
                    }
                }

                if (selectedFeed == HomeFeedType.FORUM) {
                    item {
                        HomeForumFilterControls(
                            filterState = homeState.forumFilter,
                            onCategorySelected = homeViewModel::selectForumCategory,
                            onClear = homeViewModel::clearForumCategoryFilter,
                            followedCategoryKeys = homeState.followedForumCategoryKeys,
                            onToggleCategoryFollow = { category -> homeViewModel.toggleForumCategoryFollow(category) },
                        )
                    }
                }

                if (activeFeedState.notice == HomeFeedNotice.OFFLINE_BANNER ||
                    activeFeedState.notice == HomeFeedNotice.STALE_BANNER
                ) {
                    item {
                        HomeFeedBanner(
                            selectedFeed = selectedFeed,
                            notice = activeFeedState.notice,
                            lastSyncedAtEpochMs = activeFeedState.lastSyncedAtEpochMs,
                        )
                    }
                }

                if (activeFeedState.isLoading && activeFeedState.items.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }

                if (!activeFeedState.isLoading && activeFeedState.items.isEmpty()) {
                    item {
                        HomeEmptyState(
                            selectedFeed = selectedFeed,
                            notice = activeFeedState.notice,
                            onRetry = homeViewModel::refreshSelectedFeed,
                            onShowDebits = { selectFeed(HomeFeedType.DEBITS) },
                            onShowForum = { selectFeed(HomeFeedType.FORUM) },
                            onQuickSearchClick = onQuickSearchClick,
                            onMapClick = onMapClick,
                            hasCachedDebits = homeState.debitFeed.items.isNotEmpty(),
                            hasCachedForum = homeState.forumFeed.items.isNotEmpty(),
                            isDebitFilterActive = homeState.debitGeoFilter.hasActiveFilter(),
                            onClearDebitFilters = homeViewModel::clearDebitGeoFilter,
                        )
                    }
                }

                when (selectedFeed) {
                    HomeFeedType.DEBITS -> {
                        items(
                            items = homeState.debitFeed.items,
                            key = { latestDebitItemKey(it) },
                        ) { debit ->
                            val isExternalOnly = homeState.isLocalCanyonCatalogLoaded &&
                                debit.canyonId !in homeState.localCanyonIds
                            DebitCard(
                                debit = debit,
                                isExternalOnly = isExternalOnly,
                                onClick = {
                                    if (isExternalOnly) {
                                        openExternalUrl(context, descenteCanyonCanyonUrl(debit.canyonId))
                                    } else {
                                        onCanyonClick(debit.canyonId)
                                    }
                                },
                            )
                        }
                    }

                    HomeFeedType.FORUM -> {
                        items(
                            items = homeState.forumFeed.items,
                            key = { forumTopicItemKey(it) },
                        ) { topic ->
                            val isCategoryFollowed = forumCategoryKey(topic.forumId, topic.forumName) in homeState.followedForumCategoryKeys
                            val isThreadFollowed = topic.topicId in homeState.followedForumThreadIds
                            ForumTopicCard(
                                topic = topic,
                                isCategoryFollowed = isCategoryFollowed,
                                isThreadFollowed = isThreadFollowed,
                                onClick = {
                                    val url = topic.lastMessageUrl.ifBlank { topic.topicUrl }
                                    openExternalUrl(context, url)
                                },
                                onToggleCategoryFollow = {
                                    toggleFollowAskingPermission(isCategoryFollowed) {
                                        homeViewModel.toggleForumCategoryFollow(
                                            ForumCategory(forumId = topic.forumId, forumName = topic.forumName, forumUrl = topic.topicUrl),
                                        )
                                    }
                                },
                                onToggleThreadFollow = {
                                    toggleFollowAskingPermission(isThreadFollowed) { homeViewModel.toggleForumThreadFollow(topic) }
                                },
                            )
                        }
                    }
                }

                item {
                    CreditCard()
                }
            }
        }
    }
}

private const val LOAD_MORE_VISIBLE_THRESHOLD = 3

internal fun latestDebitItemKey(debit: Debit): String = buildString {
    append(debit.canyonId)
    append('-')
    append(debit.date)
    append('-')
    append(debit.auteur.orEmpty())
}

internal fun forumTopicItemKey(topic: ForumActiveTopic): String = buildString {
    append(topic.topicId)
    append('-')
    append(topic.lastPostedAtEpochMs ?: topic.lastPostedAtText)
}
