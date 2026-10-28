package fr.descentecanyon.app.ui.outing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.MessageAudience
import fr.descentecanyon.app.ui.common.needsNotificationPermission
import fr.descentecanyon.app.ui.common.startActivitySafely
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.DcDatePickerDialog
import fr.descentecanyon.app.ui.design.DcDropdownMenu
import fr.descentecanyon.app.ui.design.DcDropdownMenuItem
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.test.TestTags
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.launch

/** What opens over the plan: one sheet or dialog at a time. */
private enum class PlanOverlay { CANYONS, DAY, DEPARTURE, GROUP, ALERT_MARGIN, NOTIFY, BAG, DELETE }

/**
 * The plan of an outing, answer first: the day, the departure and the group in three lines, the key
 * times and what may call the outing off, the details folded below; one action at the bottom
 * follows the outing from its confirmation to the return.
 */
@Composable
fun OutingPlanScreen(
    onBackClick: () -> Unit,
    onOpenEmergency: (Int) -> Unit,
    onReportFlow: (Int) -> Unit,
    onOpenCanyon: (canyonId: Int) -> Unit,
    onManagePlaces: () -> Unit,
    onOpenOutingSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OutingPlanViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val canyonPicker by viewModel.canyonPicker.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale
    val timeFormatter = rememberTimeFormatter()
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)
    var overlay by rememberSaveable { mutableStateOf<PlanOverlay?>(null) }
    var notifyAudience by rememberSaveable { mutableStateOf(MessageAudience.RELATIVE) }
    var detailExpanded by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    fun openNotify(audience: MessageAudience) {
        notifyAudience = audience
        overlay = PlanOverlay.NOTIFY
    }

    // The outing notifications (emergency access, end reminder) need the permission on Android 13+.
    fun confirmTracking() {
        val message = if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            resources.getString(R.string.outing_saved, state.latestBack?.toLocalTime()?.format(timeFormatter).orEmpty())
        } else {
            resources.getString(R.string.outing_notifications_disabled)
        }
        scope.launch { snackbarHostState.showSnackbar(message) }
    }
    val activationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        confirmTracking()
    }

    fun activate() {
        viewModel.confirm()
        if (context.needsNotificationPermission()) {
            activationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            confirmTracking()
        }
    }

    val shareTitle = stringResource(R.string.outing_message_share_title)
    var pendingShare by rememberSaveable { mutableStateOf<String?>(null) }
    fun share(message: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
        context.startActivity(Intent.createChooser(intent, shareTitle))
    }
    val sharePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingShare?.let(::share)
        pendingShare = null
    }

    // A canyon given to a day of a stay is planned: as a confirmed outing, notifications are asked for.
    LaunchedEffect(state.addedToStay) {
        if (!state.addedToStay) return@LaunchedEffect
        viewModel.addedToStayShown()
        if (context.needsNotificationPermission()) {
            activationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            confirmTracking()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CompactAppBar(
                title = state.title ?: stringResource(R.string.outing_plan_title),
                navigation = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // The topo of the canyon, read again while preparing; with several, the list of the day says which.
                    state.canyon?.canyon?.id?.let { canyonId ->
                        IconButton(
                            onClick = { if (state.nextCanyons.isEmpty()) onOpenCanyon(canyonId) else overlay = PlanOverlay.CANYONS },
                            modifier = Modifier.testTag(TestTags.OUTING_OPEN_SHEET),
                        ) {
                            Icon(Icons.Default.Description, contentDescription = stringResource(R.string.outing_open_sheet))
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag(TestTags.OUTING_MENU)) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_actions))
                        }
                        DcDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DcDropdownMenuItem(
                                text = stringResource(R.string.outing_notify_other),
                                icon = Icons.AutoMirrored.Filled.Send,
                                onClick = {
                                    menu = false
                                    openNotify(
                                        if (state.savedOuting?.relativeInformed ==
                                            true
                                        ) {
                                            MessageAudience.TEAM
                                        } else {
                                            MessageAudience.RELATIVE
                                        },
                                    )
                                },
                            )
                            DcDropdownMenuItem(
                                text = stringResource(R.string.outing_calendar_action),
                                icon = Icons.Default.CalendarMonth,
                                onClick = {
                                    menu = false
                                    addToCalendar(context, state, locale)
                                },
                            )
                            DcDropdownMenuItem(
                                text = stringResource(R.string.outing_settings_title),
                                icon = Icons.Default.Tune,
                                onClick = {
                                    menu = false
                                    onOpenOutingSettings()
                                },
                            )
                            if (state.savedOuting != null) {
                                DcDropdownMenuItem(
                                    text = stringResource(R.string.outing_delete_action),
                                    icon = Icons.Default.Delete,
                                    onClick = {
                                        menu = false
                                        overlay = PlanOverlay.DELETE
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!state.isLoading) {
                PlanBottomBar(
                    state = state,
                    onActivate = ::activate,
                    onNotify = {
                        val relative = state.savedOuting?.relativeInformed != true || state.alertChangedSinceInformed
                        openNotify(if (relative) MessageAudience.RELATIVE else MessageAudience.TEAM)
                    },
                    onEnd = {
                        val ended = state.savedOuting
                        viewModel.markBack()
                        if (ended?.relativeInformed == true) share(backMessage(context, state, locale))
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = resources.getString(R.string.outing_back_report_prompt),
                                actionLabel = resources.getString(R.string.outing_report_action),
                            )
                            if (result == SnackbarResult.ActionPerformed) state.canyon?.canyon?.id?.let(onReportFlow)
                        }
                    },
                    onDelay = { minutes ->
                        val informed = state.savedOuting?.relativeInformed == true
                        val newAlert = state.alertAt?.plusMinutes(minutes.toLong())
                        viewModel.delayAlert(minutes)
                        if (informed && newAlert != null) share(delayMessage(context, state, newAlert, locale))
                    },
                    onEmergency = { state.canyon?.canyon?.id?.let(onOpenEmergency) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.fillMaxHeight().width(contentWidth).testTag(TestTags.OUTING_PLAN_LIST),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Keyed: the status of a kept outing comes and goes without moving the state of the cards below.
                if (state.savedOuting != null) {
                    item(key = "status") {
                        FollowStatusCard(state = state, timeFormatter = timeFormatter, onNotify = { openNotify(MessageAudience.RELATIVE) })
                    }
                }
                item(key = "settings") {
                    PlanSettingsCard(
                        state = state,
                        onCanyonsClick = { overlay = PlanOverlay.CANYONS },
                        onDayClick = { overlay = PlanOverlay.DAY },
                        onDepartureClick = { overlay = PlanOverlay.DEPARTURE },
                        onGroupClick = { overlay = PlanOverlay.GROUP },
                    )
                }
                item(key = "summary") {
                    SummaryCard(state = state, timeFormatter = timeFormatter, onAlertClick = { overlay = PlanOverlay.ALERT_MARGIN })
                }
                state.memo?.let { memo -> item(key = "memo") { MemoCard(memo = memo) } }
                item(key = "detail") {
                    DayDetailCard(
                        state = state,
                        timeFormatter = timeFormatter,
                        expanded = detailExpanded,
                        onToggle = { detailExpanded = !detailExpanded },
                        onAdjust = viewModel::adjustPhase,
                    )
                }
                item(key = "bag") { BagCard(state = state, onClick = { overlay = PlanOverlay.BAG }) }
            }
        }
    }

    val dismiss = { overlay = null }
    when (overlay) {
        PlanOverlay.CANYONS -> CanyonsSheet(
            state = state,
            picker = canyonPicker,
            onDismiss = dismiss,
            onSearch = viewModel::searchCanyons,
            onClosePicker = viewModel::closeCanyonPicker,
            onAdd = viewModel::addCanyon,
            onRemove = viewModel::removeCanyon,
            onLinkSelected = viewModel::selectLink,
            onOpenCanyon = { canyonId ->
                overlay = null
                onOpenCanyon(canyonId)
            },
        )

        PlanOverlay.DAY -> DcDatePickerDialog(
            initialDate = state.choices.date,
            onDateSelected = viewModel::selectDate,
            onDismiss = dismiss,
            minDate = state.today,
        )

        PlanOverlay.DEPARTURE -> DepartureSheet(
            state = state,
            onDismiss = dismiss,
            onPlaceSelected = viewModel::selectPlace,
            onDepartNow = viewModel::departNow,
            onTimeSelected = viewModel::selectAnchorTime,
            onManagePlaces = {
                overlay = null
                onManagePlaces()
            },
            onSetManualDrive = viewModel::setManualDrive,
        )

        PlanOverlay.GROUP -> GroupSheet(
            state = state,
            onDismiss = dismiss,
            onSizeSelected = viewModel::selectGroupSize,
            onLevelSelected = viewModel::selectGroupLevel,
            onShuttleSelected = viewModel::selectShuttle,
        )

        PlanOverlay.ALERT_MARGIN -> AlertMarginDialog(state = state, onMarginSelected = viewModel::selectAlertMargin, onDismiss = dismiss)

        PlanOverlay.NOTIFY -> NotifySheet(
            state = state,
            initialAudience = notifyAudience,
            onDismiss = dismiss,
            onNoteChanged = viewModel::setNote,
            onMeetAtPlaceChanged = viewModel::setMeetAtPlace,
            onSaveSections = viewModel::saveMessageSections,
            onSend = { audience, message ->
                overlay = null
                if (audience == MessageAudience.RELATIVE) {
                    // The relative counts on its alert time: sending the plan plans the outing, so the app
                    // reminds the user at the return and keeps the emergency screen at hand.
                    val wasPlanned = state.savedOuting != null
                    viewModel.markRelativeInformed()
                    if (!wasPlanned && context.needsNotificationPermission()) {
                        pendingShare = message
                        sharePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        return@NotifySheet
                    }
                }
                share(message)
            },
        )

        PlanOverlay.BAG -> BagDialog(
            state = state,
            onDismiss = dismiss,
            onToggle = viewModel::toggleGear,
            onHide = viewModel::hideGear,
            onRemoveCustom = viewModel::removeCustomGear,
            onAdd = viewModel::addCustomGear,
            onRestore = viewModel::restoreSuggestedGear,
        )

        PlanOverlay.DELETE -> AlertDialog(
            onDismissRequest = dismiss,
            text = { Text(stringResource(R.string.outing_cancel_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    overlay = null
                    viewModel.deleteOuting()
                }) { Text(stringResource(R.string.outing_delete_action)) }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
        )

        null -> Unit
    }
}

/** "Back from the canyon at 17:10, all is well", for the relative who waits for news. */
private fun backMessage(context: Context, state: OutingPlanUiState, locale: Locale): String {
    val strings = ResourceOutingMessageStrings(context.resources)
    val now = LocalDateTime.now(ZoneId.systemDefault())
    return strings.back(state.title.orEmpty(), timeOfOuting(now, now.toLocalDate(), locale))
}

/** The new alert time, for the relative who waits for news. */
private fun delayMessage(context: Context, state: OutingPlanUiState, alertAt: LocalDateTime, locale: Locale): String {
    val strings = ResourceOutingMessageStrings(context.resources)
    return strings.delay(
        state.title.orEmpty(),
        timeOfOuting(alertAt, state.choices.date, locale),
        state.emergencyNumbers.first().number,
    )
}

/** An event of the whole day in the calendar app, with the message for the team as its description. */
private fun addToCalendar(context: Context, state: OutingPlanUiState, locale: Locale) {
    val title = state.title ?: return
    val schedule = state.schedule ?: return
    val begin = schedule.leavePlace ?: schedule.arriveParking
    val end = schedule.backAtPlace?.estimate ?: schedule.readyToLeave?.estimate ?: state.expectedBack ?: return
    val zone = ZoneId.systemDefault()
    val resources = context.resources
    val description = outingMessageData(state, resources, locale)?.let { data ->
        buildOutingMessage(
            ResourceOutingMessageStrings(resources),
            data,
            MessageAudience.TEAM,
            MessageAudience.TEAM.defaultSections,
        )
    }
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin.atZone(zone).toInstant().toEpochMilli())
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.atZone(zone).toInstant().toEpochMilli())
        .putExtra(CalendarContract.Events.TITLE, resources.getString(R.string.outing_calendar_title, title))
        .putExtra(CalendarContract.Events.DESCRIPTION, description)
    state.meetingParking?.let { point ->
        intent.putExtra(CalendarContract.Events.EVENT_LOCATION, String.format(Locale.ROOT, "%.5f, %.5f", point.latitude, point.longitude))
    }
    context.startActivitySafely(intent)
}
