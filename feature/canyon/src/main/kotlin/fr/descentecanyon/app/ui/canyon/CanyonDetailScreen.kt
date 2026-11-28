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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.AppCanyons
import fr.descentecanyon.app.domain.model.AppLinks
import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonSection
import fr.descentecanyon.app.domain.model.FirstStep
import fr.descentecanyon.app.domain.model.Hint
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.perf.PerformanceTrace
import fr.descentecanyon.app.ui.common.LocalAppLinks
import fr.descentecanyon.app.ui.common.asString
import fr.descentecanyon.app.ui.common.needsNotificationPermission
import fr.descentecanyon.app.ui.common.openUrl
import fr.descentecanyon.app.ui.common.startActivitySafely
import fr.descentecanyon.app.ui.components.AppFloatingActionButton
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.components.EmergencyIconButton
import fr.descentecanyon.app.ui.components.longDayLabel
import fr.descentecanyon.app.ui.design.DcDropdownMenu
import fr.descentecanyon.app.ui.design.DcDropdownMenuItem
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.onboarding.DcHint
import fr.descentecanyon.app.ui.onboarding.LocalOnboarding
import fr.descentecanyon.app.ui.test.TestTags
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    /** The outing already planned in this canyon (its first canyon, its id): opened rather than a new one. */
    onOpenOuting: (canyonId: Int, outingId: Long) -> Unit = { _, _ -> },
    /** Opened from the search of a day of a stay: the outing is planned for that day. */
    planForDay: LocalDate? = null,
    onOpenEmergency: () -> Unit = {},
    /** A flow report for an outing already logged: only its day is prefilled. */
    onReportOuting: (LogbookEntry) -> Unit = {},
    onOpenLogbook: () -> Unit = {},
    /** A change to a canyon added in the app (the site's sheets are changed on the site). */
    onProposeEdit: () -> Unit = {},
    /** The tab a notification or a link opens (the topo when null). */
    initialSection: CanyonSection? = null,
    modifier: Modifier = Modifier,
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
    // Flow reports, their follow-up and the interest votes live on the site, which does not know this canyon.
    val isAddedInApp = AppCanyons.isAppCanyon(canyonId)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { viewModel.toggleDebitNotifications() }

    // Opened from the "My canyon" card or from the ⋮ menu: shown over the page whichever opened them.
    val myCanyonDialogs = rememberMyCanyonDialogs()
    uiState.canyonDetail?.canyon?.let { canyon ->
        MyCanyonDialogsHost(
            dialogs = myCanyonDialogs,
            canyonId = canyon.id,
            canyonName = canyon.nom,
            memo = uiState.personalNote,
            entries = uiState.logbook,
            knownCompanions = uiState.knownCompanions,
            onSaveMemo = viewModel::savePersonalNote,
            onSaveEntry = viewModel::saveLogbookEntry,
            onDeleteEntry = viewModel::deleteLogbookEntry,
            onReportFlow = onReportOuting,
            canReportFlow = !isAddedInApp,
        )
    }

    val onboarding = LocalOnboarding.current
    LaunchedEffect(canyonId) {
        PerformanceTrace.logEvent("canyon_detail_screen_visible", "canyonId" to canyonId)
        onboarding.markStepDone(FirstStep.OPEN_CANYON)
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
                viewModel.onResumed()
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
                    EmergencyIconButton(onClick = onOpenEmergency)
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
                        isAddedInApp = isAddedInApp,
                        onProposeEdit = onProposeEdit,
                        // Nothing of the user's here yet: no card above the tabs, its two actions are in the menu.
                        myCanyonDialogs = myCanyonDialogs.takeIf {
                            uiState.canyonDetail != null && !hasMyCanyon(uiState.personalNote, uiState.logbook)
                        },
                    )
                },
            )
        },
        // The two actions people come for, under the thumb: plan the outing (for the day of a stay,
        // or the one already planned) and report the flow seen. The app's tabs are not shown here.
        bottomBar = {
            if (uiState.canyonDetail != null && !uiState.isLoading && uiState.error == null) {
                val planned = uiState.plannedOuting.takeIf { planForDay == null }
                CanyonActionBar(
                    planLabel = when {
                        planForDay != null -> stringResource(R.string.outing_plan_for_day, longDayLabel(planForDay, capitalized = false))
                        planned != null -> stringResource(R.string.outing_open_planned, shortDayLabel(planned))
                        else -> stringResource(R.string.outing_plan_action)
                    },
                    onPlan = { if (planned != null) onOpenOuting(planned.canyonId, planned.id) else onPlanOuting() },
                    // Planning a day of a stay, the flow seen today is beside the point: one button, its day in full.
                    onReportDebit = onReportDebitClick.takeUnless { isAddedInApp || planForDay != null },
                )
            }
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
                            modifier = Modifier.padding(horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = uiState.error?.asString() ?: stringResource(R.string.error_generic),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
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
                            initialSection = initialSection,
                            personalNote = uiState.personalNote,
                            nearbyCanyons = uiState.nearbyCanyons,
                            onShowMap = onShowMapClick,
                            logbook = uiState.logbook,
                            myCanyonDialogs = myCanyonDialogs,
                            onReportFlow = onReportOuting,
                            onOpenLogbook = onOpenLogbook,
                            onNearbyCanyonClick = onOpenCanyon,
                            bottomContentPadding = 24.dp,
                            listState = listState,
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(contentWidth),
                        )
                    }
                }
            }
        }
    }
}

/** Plan the outing (or open the one planned), and report the flow: labelled buttons above the navigation bar. */
@Composable
private fun CanyonActionBar(planLabel: String, onPlan: () -> Unit, onReportDebit: (() -> Unit)?) {
    val colors = LocalDcColors.current
    Surface(
        color = colors.backgroundElevated,
        contentColor = colors.textPrimary,
        border = BorderStroke(1.dp, colors.borderSubtle),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val labelStyle = MaterialTheme.typography.labelLarge
            // Narrow phones: less padding than a default button, and the labels shrink before they are cut.
            val padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            // Each weight on a box of its own: the hint around a button does not pass it to the row.
            Box(modifier = Modifier.weight(1f)) {
                DcHint(
                    hint = Hint.CANYON_PLAN,
                    title = stringResource(R.string.outing_plan_action),
                    text = stringResource(R.string.hint_canyon_plan_body),
                ) {
                    Button(
                        onClick = onPlan,
                        contentPadding = padding,
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.DETAIL_PLAN_OUTING),
                    ) {
                        Icon(Icons.Default.Hiking, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text = planLabel,
                            style = labelStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = labelStyle.fontSize),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
            onReportDebit?.let { report ->
                Box(modifier = Modifier.weight(1f)) {
                    DcHint(
                        hint = Hint.CANYON_REPORT,
                        title = stringResource(R.string.canyon_action_report_debit),
                        text = stringResource(R.string.hint_canyon_report_body),
                    ) {
                        OutlinedButton(
                            onClick = report,
                            contentPadding = padding,
                            modifier = Modifier.fillMaxWidth().testTag(TestTags.DETAIL_REPORT_DEBIT_BUTTON),
                        ) {
                            Icon(Icons.Default.WaterDrop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                text = stringResource(R.string.canyon_action_report_debit),
                                style = labelStyle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = labelStyle.fontSize),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "28 nov.": the day of the outing, in the canyon's zone when it has one. */
@Composable
private fun shortDayLabel(outing: PlannedOuting): String {
    val locale = LocalLocale.current.platformLocale
    return DateTimeFormatter.ofPattern("d MMM", locale).format(outing.dayIn(ZoneId.systemDefault()))
}

/** Less frequent actions, kept in a menu so the canyon name stays readable in the top bar. */
@Composable
private fun CanyonMoreActions(
    canyon: Canyon?,
    isFollowingDebits: Boolean,
    onToggleFollowDebits: () -> Unit,
    onRateInterestClick: () -> Unit,
    isAddedInApp: Boolean,
    onProposeEdit: () -> Unit,
    /** Set while the "My canyon" card is not shown: its actions are offered here. */
    myCanyonDialogs: MyCanyonDialogs?,
) {
    val context = LocalContext.current
    val appLinks = LocalAppLinks.current
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
            if (isAddedInApp) {
                DcDropdownMenuItem(
                    text = stringResource(R.string.canyon_propose_edit),
                    icon = Icons.Default.EditNote,
                    onClick = {
                        expanded = false
                        onProposeEdit()
                    },
                    modifier = Modifier.testTag(TestTags.DETAIL_PROPOSE_EDIT),
                )
            } else {
                SiteMenuItems(
                    isFollowingDebits = isFollowingDebits,
                    onToggleFollowDebits = {
                        expanded = false
                        onToggleFollowDebits()
                    },
                    onRateInterestClick = {
                        expanded = false
                        onRateInterestClick()
                    },
                )
            }
            myCanyonDialogs?.let { dialogs ->
                DcDropdownMenuItem(
                    text = stringResource(R.string.my_canyon_add_memo),
                    icon = Icons.AutoMirrored.Filled.NoteAdd,
                    onClick = {
                        expanded = false
                        dialogs.editingMemo = true
                    },
                    modifier = Modifier.testTag(TestTags.DETAIL_MEMO),
                )
                DcDropdownMenuItem(
                    text = stringResource(R.string.my_canyon_descended),
                    icon = Icons.Default.CheckCircle,
                    onClick = {
                        expanded = false
                        dialogs.addingEntry = true
                    },
                    modifier = Modifier.testTag(TestTags.DETAIL_LOGBOOK_ADD),
                )
            }
            if (canyon != null) {
                DcDropdownMenuItem(
                    text = stringResource(R.string.share_canyon),
                    icon = Icons.Default.Share,
                    onClick = {
                        expanded = false
                        context.shareCanyon(canyon, appLinks)
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

/** The follow-up of the flow reports and the interest vote, both on the site. */
@Composable
private fun SiteMenuItems(isFollowingDebits: Boolean, onToggleFollowDebits: () -> Unit, onRateInterestClick: () -> Unit) {
    DcDropdownMenuItem(
        text = stringResource(
            if (isFollowingDebits) R.string.notifications_canyon_unfollow_action else R.string.notifications_canyon_follow_action,
        ),
        icon = if (isFollowingDebits) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
        iconTint = if (isFollowingDebits) null else LocalDcColors.current.textSecondary,
        onClick = onToggleFollowDebits,
        modifier = Modifier.testTag(TestTags.DETAIL_DEBIT_NOTIFICATION_BUTTON),
    )
    DcDropdownMenuItem(
        text = stringResource(R.string.interest_rating_add_action),
        icon = Icons.Default.Star,
        onClick = onRateInterestClick,
        modifier = Modifier.testTag(TestTags.DETAIL_RATE_INTEREST_BUTTON),
    )
}

private fun Context.shareCanyon(canyon: Canyon, appLinks: AppLinks) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, canyon.nom)
        .putExtra(Intent.EXTRA_TEXT, canyonShareText(canyon, appLinks))
    startActivitySafely(Intent.createChooser(send, null))
}

/**
 * "Name (rating)", where it is, and the link: the app's own one, which opens the canyon in the app
 * for those who have it and shows it (with its sheet on the site) to the others; a canyon added in
 * the app has no other. The site's address without the app's domain.
 */
internal fun canyonShareText(canyon: Canyon, appLinks: AppLinks): String {
    val title = if (canyon.cotation.isBlank()) canyon.nom else "${canyon.nom} (${canyon.cotation})"
    val place = listOfNotNull(
        canyon.communes.joinToString(", ").ifBlank { canyon.commune }.takeIf { it.isNotBlank() },
        canyon.departement?.takeIf { it.isNotBlank() },
        canyon.pays.takeIf { it.isNotBlank() },
    ).distinct().joinToString(" · ")
    val link = appLinks.canyon(canyon.id, canyon.nom) ?: canyon.url
    return listOf(title, place, link).filter { it.isNotBlank() }.joinToString("\n")
}
