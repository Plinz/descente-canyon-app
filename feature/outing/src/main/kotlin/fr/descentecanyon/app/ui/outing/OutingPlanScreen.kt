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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.test.TestTags
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun OutingPlanScreen(
    onBackClick: () -> Unit,
    onOpenEmergency: (Int) -> Unit,
    onReportFlow: (Int) -> Unit,
    onManagePlaces: () -> Unit,
    onOpenOutingSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OutingPlanViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val timeFormatter = rememberTimeFormatter()
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)
    val appLocale = LocalLocale.current.platformLocale
    var messageLanguage by rememberSaveable {
        mutableStateOf(MESSAGE_LANGUAGES.firstOrNull { it.language == appLocale.language }?.language)
    }
    val messageLocale = MESSAGE_LANGUAGES.firstOrNull { it.language == messageLanguage } ?: appLocale

    // The outing notifications (emergency access, end reminder) need the permission on Android 13+.
    fun confirmSaved() {
        val message = if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            resources.getString(R.string.outing_saved, state.latestBack?.toLocalTime()?.format(timeFormatter).orEmpty())
        } else {
            resources.getString(R.string.outing_notifications_disabled)
        }
        scope.launch { snackbarHostState.showSnackbar(message) }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { confirmSaved() }

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

    fun save() {
        val isNew = state.savedOuting == null
        val alertChanged = state.alertChangedSinceInformed
        viewModel.saveOuting()
        when {
            isNew && context.needsNotificationPermission() -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

            alertChanged -> scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = resources.getString(R.string.outing_alert_changed),
                    actionLabel = resources.getString(R.string.outing_alert_changed_action),
                )
                if (result == SnackbarResult.ActionPerformed) {
                    state.alertAt?.let { share(delayMessage(context, state, it, messageLocale)) }
                }
            }

            else -> confirmSaved()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CompactAppBar(
                title = state.canyon?.canyon?.nom ?: stringResource(R.string.outing_plan_title),
                navigation = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (!state.isLoading && (state.savedOuting == null || state.hasUnsavedChanges)) {
                        IconButton(onClick = ::save, modifier = Modifier.testTag(TestTags.OUTING_TOP_SAVE)) {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = stringResource(
                                    if (state.savedOuting == null) R.string.outing_save_action else R.string.outing_update_action,
                                ),
                            )
                        }
                    }
                    IconButton(onClick = onOpenOutingSettings) {
                        Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.outing_settings_title))
                    }
                },
            )
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
                // Keyed: the banner of a saved outing comes and goes without moving the state of the cards below.
                state.memo?.let { memo -> item(key = "memo") { MemoCard(memo = memo) } }
                state.savedOuting?.let { item(key = "status") { OutingStatusBanner(state = state) } }
                item(key = "when") {
                    WhenCard(
                        state = state,
                        onDateSelected = viewModel::selectDate,
                        onTimeSelected = viewModel::selectAnchorTime,
                        onAnchorSelected = viewModel::selectAnchor,
                        onPlaceSelected = viewModel::selectPlace,
                        onManagePlaces = onManagePlaces,
                        onSetManualDrive = viewModel::setManualDrive,
                    )
                }
                item(key = "group") {
                    GroupCard(
                        state = state,
                        onSizeSelected = viewModel::selectGroupSize,
                        onLevelSelected = viewModel::selectGroupLevel,
                        onShuttleSelected = viewModel::selectShuttle,
                    )
                }
                item(key = "timeline") {
                    TimelineCard(
                        state = state,
                        timeFormatter = timeFormatter,
                        onAdjust = viewModel::adjustPhase,
                        onOpenSettings = onOpenOutingSettings,
                    )
                }
                item(key = "conditions") { ConditionsCard(state = state) }
                item(key = "gear") {
                    GearCard(
                        state = state,
                        onToggle = viewModel::toggleGear,
                        onHide = viewModel::hideGear,
                        onRemoveCustom = viewModel::removeCustomGear,
                        onAdd = viewModel::addCustomGear,
                        onRestore = viewModel::restoreSuggestedGear,
                    )
                }
                item(key = "messages") {
                    MessagesCard(
                        state = state,
                        messageLocale = messageLocale,
                        onLanguageSelected = { messageLanguage = it.language },
                        onMarginSelected = viewModel::selectAlertMargin,
                        onNoteChanged = viewModel::setNote,
                        onMeetAtPlaceChanged = viewModel::setMeetAtPlace,
                        onSaveSections = viewModel::saveMessageSections,
                        onSend = { audience, message ->
                            if (audience == MessageAudience.RELATIVE) {
                                // The relative counts on its alert time: sending the plan saves the outing, so the app
                                // reminds the user at the return and keeps the emergency screen at hand.
                                val isNew = state.savedOuting == null
                                viewModel.markRelativeInformed()
                                if (isNew && context.needsNotificationPermission()) {
                                    pendingShare = message
                                    sharePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    return@MessagesCard
                                }
                            }
                            share(message)
                        },
                    )
                }
                item(key = "follow") {
                    OutingStatusCard(
                        state = state,
                        onSave = ::save,
                        onEnd = {
                            val ended = state.savedOuting
                            viewModel.markBack()
                            if (ended?.relativeInformed == true) {
                                share(backMessage(context, state, messageLocale))
                            }
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
                            if (informed && newAlert != null) share(delayMessage(context, state, newAlert, messageLocale))
                        },
                        onAddToCalendar = { addToCalendar(context, state, messageLocale) },
                        onCancel = viewModel::cancelOuting,
                    )
                }
                item(key = "emergency") {
                    OutlinedButton(
                        onClick = { state.canyon?.canyon?.id?.let(onOpenEmergency) },
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.OUTING_EMERGENCY_BUTTON),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Default.Emergency, contentDescription = null)
                        Text(stringResource(R.string.outing_emergency_action), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/** "Back from the canyon at 17:10, all is well", for the relative who waits for news. */
private fun backMessage(context: Context, state: OutingPlanUiState, locale: Locale): String {
    val strings = ResourceOutingMessageStrings(context.resourcesIn(locale))
    val now = LocalDateTime.now(ZoneId.systemDefault())
    return strings.back(state.canyon?.canyon?.nom.orEmpty(), timeOfOuting(now, now.toLocalDate(), locale))
}

/** The new alert time, for the relative who waits for news. */
private fun delayMessage(context: Context, state: OutingPlanUiState, alertAt: LocalDateTime, locale: Locale): String {
    val strings = ResourceOutingMessageStrings(context.resourcesIn(locale))
    return strings.delay(
        state.canyon?.canyon?.nom.orEmpty(),
        timeOfOuting(alertAt, state.choices.date, locale),
        state.emergencyNumbers.first().number,
    )
}

/** An event of the whole day in the calendar app, with the message for the team as its description. */
private fun addToCalendar(context: Context, state: OutingPlanUiState, locale: Locale) {
    val canyon = state.canyon?.canyon ?: return
    val schedule = state.schedule ?: return
    val begin = schedule.leavePlace ?: schedule.arriveParking
    val end = schedule.backAtPlace?.estimate ?: schedule.readyToLeave?.estimate ?: state.expectedBack ?: return
    val zone = ZoneId.systemDefault()
    val resources = context.resourcesIn(locale)
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
        .putExtra(CalendarContract.Events.TITLE, resources.getString(R.string.outing_calendar_title, canyon.nom))
        .putExtra(CalendarContract.Events.DESCRIPTION, description)
    state.meetingParking?.let { point ->
        intent.putExtra(CalendarContract.Events.EVENT_LOCATION, String.format(Locale.ROOT, "%.5f, %.5f", point.latitude, point.longitude))
    }
    context.startActivitySafely(intent)
}
