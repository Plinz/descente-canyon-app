package fr.descentecanyon.app.ui.canyon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.perf.PerformanceTrace
import fr.descentecanyon.app.ui.common.asString
import fr.descentecanyon.app.ui.common.needsNotificationPermission
import fr.descentecanyon.app.ui.common.openUrl
import fr.descentecanyon.app.ui.common.startActivitySafely
import fr.descentecanyon.app.ui.components.AppFloatingActionButton
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.components.EmergencyIconButton
import fr.descentecanyon.app.ui.design.DcDropdownMenu
import fr.descentecanyon.app.ui.design.DcDropdownMenuItem
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.test.TestTags
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanyonDetailScreen(
    canyonId: Int,
    onBackClick: () -> Unit,
    onReportDebitClick: () -> Unit,
    onRateInterestClick: () -> Unit,
    onShowMapClick: () -> Unit,
    onOpenPredictionInfo: () -> Unit,
    onOpenPhotoGallery: (Long) -> Unit,
    onUserClick: (String) -> Unit = {},
    onOpenCanyon: (Int) -> Unit = {},
    onPlanOuting: () -> Unit = {},
    /** Opened from the search of a day of a stay: the outing is planned for that day. */
    planForDay: LocalDate? = null,
    onStartOutingNow: () -> Unit = {},
    onOpenEmergency: () -> Unit = {},
    /** A flow report prefilled from an outing just logged (date, descended, flow seen). */
    onReportOuting: (LogbookEntry) -> Unit = {},
    onOpenLogbook: () -> Unit = {},
    openDebitsTabInitially: Boolean = false,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    refreshDebitsAfterSubmission: Boolean = false,
    onRefreshDebitsAfterSubmissionHandled: () -> Unit = {},
    refreshDetailAfterInterestRating: Boolean = false,
    onRefreshDetailAfterInterestRatingHandled: () -> Unit = {},
    viewModel: CanyonDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val contentWidth = rememberDcContentWidth(maxWidth = 940.dp)
    val listState = rememberLazyListState()
    // The map button would cover the content being read: it hides while scrolling down.
    val showMapButton = listState.isAtTopOrScrollingUp()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { viewModel.toggleDebitNotifications() }

    LaunchedEffect(canyonId) {
        PerformanceTrace.logEvent("canyon_detail_screen_visible", "canyonId" to canyonId)
    }

    LaunchedEffect(refreshDebitsAfterSubmission) {
        if (refreshDebitsAfterSubmission) {
            viewModel.refreshDebitsAfterSubmission()
            onRefreshDebitsAfterSubmissionHandled()
        }
    }

    LaunchedEffect(refreshDetailAfterInterestRating) {
        if (refreshDetailAfterInterestRating) {
            viewModel.loadCanyon(canyonId)
            onRefreshDetailAfterInterestRatingHandled()
        }
    }

    LaunchedEffect(uiState.transientMessage) {
        uiState.transientMessage?.let { message ->
            Toast.makeText(context, message.asString(context), Toast.LENGTH_SHORT).show()
            viewModel.clearTransientMessage()
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reconcilePersistedPhotos()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CompactAppBar(
                title = uiState.canyonDetail?.canyon?.nom ?: stringResource(R.string.canyon_fallback_title, canyonId),
                navigation = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onReportDebitClick,
                        modifier = Modifier.testTag(TestTags.DETAIL_REPORT_DEBIT_BUTTON),
                    ) {
                        AddDebitIcon(contentDescription = stringResource(R.string.debit_add_action))
                    }
                    EmergencyIconButton(onClick = onOpenEmergency)
                    IconButton(
                        onClick = viewModel::toggleFavorite,
                        modifier = Modifier.testTag(TestTags.DETAIL_FAVORITE_BUTTON),
                    ) {
                        Icon(
                            imageVector = if (uiState.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (uiState.isFavorite) {
                                stringResource(R.string.remove_favorite)
                            } else {
                                stringResource(R.string.add_favorite)
                            },
                            tint = if (uiState.isFavorite) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                        )
                    }
                    CanyonMoreActions(
                        canyon = uiState.canyonDetail?.canyon,
                        isFollowingDebits = uiState.isDebitNotificationsEnabled,
                        onToggleFollowDebits = {
                            if (!uiState.isDebitNotificationsEnabled && context.needsNotificationPermission()) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.toggleDebitNotifications()
                            }
                        },
                        onRateInterestClick = onRateInterestClick,
                    )
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = uiState.error?.asString() ?: stringResource(R.string.error_generic),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = { viewModel.loadCanyon(canyonId) }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }

                uiState.canyonDetail != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        CanyonDetailContent(
                            detail = uiState.canyonDetail!!,
                            isRefreshingDetail = uiState.isRefreshingDetail,
                            isOnline = uiState.isOnline,
                            isLoadingPhotos = uiState.isLoadingPhotos,
                            photoError = uiState.photoError?.asString(),
                            isLoadingDebits = uiState.isLoadingDebits,
                            debitError = uiState.debitError?.asString(),
                            edfStatus = uiState.edfStatus,
                            isLoadingEdfStatus = uiState.isLoadingEdfStatus,
                            edfStatusError = uiState.edfStatusError?.asString(),
                            edfStatusSourceUrl = uiState.edfStatusSourceUrl,
                            weather = uiState.weather,
                            isLoadingWeather = uiState.isLoadingWeather,
                            weatherError = uiState.weatherError?.asString(),
                            predictions = uiState.predictions,
                            isLoadingPredictions = uiState.isLoadingPredictions,
                            predictionError = uiState.predictionError?.asString(),
                            onOpenPredictionInfo = onOpenPredictionInfo,
                            downloadingPhotoIds = uiState.downloadingPhotoIds,
                            onOpenPhotoGallery = onOpenPhotoGallery,
                            memberAuthors = uiState.memberAuthors,
                            onUserClick = onUserClick,
                            onPersistedPhotoMissing = viewModel::onPersistedPhotoMissing,
                            openDebitsTabInitially = openDebitsTabInitially,
                            personalNote = uiState.personalNote,
                            onSavePersonalNote = viewModel::savePersonalNote,
                            nearbyCanyons = uiState.nearbyCanyons,
                            onPlanOuting = onPlanOuting,
                            planForDay = planForDay,
                            onStartOutingNow = onStartOutingNow,
                            logbook = uiState.logbook,
                            knownCompanions = uiState.knownCompanions,
                            onSaveLogbookEntry = viewModel::saveLogbookEntry,
                            onDeleteLogbookEntry = viewModel::deleteLogbookEntry,
                            onReportFlow = onReportOuting,
                            onOpenLogbook = onOpenLogbook,
                            onNearbyCanyonClick = onOpenCanyon,
                            bottomContentPadding = contentPadding.calculateBottomPadding() + 96.dp,
                            listState = listState,
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(contentWidth),
                        )
                    }
                }
            }

            if (uiState.canyonDetail != null && !uiState.isLoading && uiState.error == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    AnimatedVisibility(visible = showMapButton, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                        AppFloatingActionButton(
                            onClick = onShowMapClick,
                            modifier = Modifier.padding(
                                end = 20.dp,
                                bottom = contentPadding.calculateBottomPadding() + 20.dp,
                            ),
                            icon = { iconModifier ->
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = stringResource(R.string.show_map_points),
                                    modifier = iconModifier,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddDebitIcon(contentDescription: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(30.dp)) {
        Icon(
            imageVector = Icons.Default.WaterDrop,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .size(26.dp)
                .align(Alignment.CenterStart),
        )
        Surface(
            modifier = Modifier
                .size(15.dp)
                .align(Alignment.BottomEnd),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

/** Less frequent actions, kept in a menu so the canyon name stays readable in the top bar. */
@Composable
private fun CanyonMoreActions(
    canyon: Canyon?,
    isFollowingDebits: Boolean,
    onToggleFollowDebits: () -> Unit,
    onRateInterestClick: () -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.more_actions),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        DcDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DcDropdownMenuItem(
                text = stringResource(
                    if (isFollowingDebits) R.string.notifications_canyon_unfollow_action else R.string.notifications_canyon_follow_action,
                ),
                icon = if (isFollowingDebits) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
                iconTint = if (isFollowingDebits) null else LocalDcColors.current.textSecondary,
                onClick = {
                    expanded = false
                    onToggleFollowDebits()
                },
                modifier = Modifier.testTag(TestTags.DETAIL_DEBIT_NOTIFICATION_BUTTON),
            )
            DcDropdownMenuItem(
                text = stringResource(R.string.interest_rating_add_action),
                icon = Icons.Default.Star,
                onClick = {
                    expanded = false
                    onRateInterestClick()
                },
                modifier = Modifier.testTag(TestTags.DETAIL_RATE_INTEREST_BUTTON),
            )
            if (canyon != null) {
                DcDropdownMenuItem(
                    text = stringResource(R.string.share_canyon),
                    icon = Icons.Default.Share,
                    onClick = {
                        expanded = false
                        context.shareCanyon(canyon)
                    },
                )
            }
            if (canyon?.url?.startsWith("https://") == true) {
                DcDropdownMenuItem(
                    text = stringResource(R.string.open_on_site),
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = {
                        expanded = false
                        context.openUrl(canyon.url)
                    },
                )
            }
        }
    }
}

private fun Context.shareCanyon(canyon: Canyon) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, canyon.nom)
        .putExtra(Intent.EXTRA_TEXT, canyonShareText(canyon))
    startActivitySafely(Intent.createChooser(send, null))
}

/** "Name (rating)", where it is, and the link: the site URL also opens the app for recipients who have it. */
internal fun canyonShareText(canyon: Canyon): String {
    val title = if (canyon.cotation.isBlank()) canyon.nom else "${canyon.nom} (${canyon.cotation})"
    val place = listOfNotNull(
        canyon.communes.joinToString(", ").ifBlank { canyon.commune }.takeIf { it.isNotBlank() },
        canyon.departement?.takeIf { it.isNotBlank() },
        canyon.pays.takeIf { it.isNotBlank() },
    ).distinct().joinToString(" · ")
    return listOf(title, place, canyon.url).filter { it.isNotBlank() }.joinToString("\n")
}

/** True at the top of the list and while it scrolls back up, false while it scrolls down. */
@Composable
private fun LazyListState.isAtTopOrScrollingUp(): Boolean {
    var previousIndex by remember(this) { mutableIntStateOf(firstVisibleItemIndex) }
    var previousOffset by remember(this) { mutableIntStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            val isUp = when {
                !canScrollBackward -> true
                previousIndex != firstVisibleItemIndex -> previousIndex > firstVisibleItemIndex
                else -> previousOffset >= firstVisibleItemScrollOffset
            }
            previousIndex = firstVisibleItemIndex
            previousOffset = firstVisibleItemScrollOffset
            isUp
        }
    }.value
}
