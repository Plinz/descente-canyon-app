package fr.descentecanyon.app.ui.account

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.model.ContributorIdentity
import fr.descentecanyon.app.domain.model.ForumUser
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.ui.auth.AuthViewModel
import fr.descentecanyon.app.ui.auth.LoginDialog
import fr.descentecanyon.app.ui.common.asString
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.components.rememberLogbookDateFormatter
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.design.rememberDcScreenHorizontalPadding
import fr.descentecanyon.app.ui.test.TestTags
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Mon espace": the account, the logbook and memos, contributions, follows and the user's data. */
@Composable
fun MyAccountScreen(
    onBackClick: () -> Unit,
    onOpenLogbook: (LogbookTab) -> Unit,
    onOpenMemberProfile: (String) -> Unit,
    onOpenOuting: (canyonId: Int, outingId: Long) -> Unit,
    onOpenOutings: () -> Unit,
    onOpenPendingDebits: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MyAccountViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)
    val horizontalPadding = rememberDcScreenHorizontalPadding()
    var showLogin by rememberSaveable { mutableStateOf(false) }
    var editingIdentity by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    val message = state.message?.asString()
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }
    val loginRequest = state.loginRequest
    LaunchedEffect(loginRequest) {
        if (loginRequest != null) {
            authViewModel.showMessage(loginRequest)
            showLogin = true
            viewModel.onLoginRequestHandled()
        }
    }
    LaunchedEffect(showLogin, authState.authState) {
        if (showLogin && authState.authState is AuthState.Connected) showLogin = false
    }

    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(JSON_MIME)) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { viewModel.onExportWritten(context.contentResolver.writeText(uri, viewModel.exportJson())) }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(CSV_MIME)) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { viewModel.onExportWritten(context.contentResolver.writeText(uri, viewModel.exportLogbookCsv())) }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { viewModel.importFile(context.contentResolver.readText(uri).orEmpty()) }
    }

    if (showLogin) {
        LoginDialog(
            uiState = authState,
            onUsernameChanged = authViewModel::onUsernameChanged,
            onPasswordChanged = authViewModel::onPasswordChanged,
            onLogin = authViewModel::login,
            onLogout = authViewModel::logout,
            onDismiss = { showLogin = false },
        )
    }
    if (editingIdentity) {
        ContributorIdentityDialog(
            identity = state.contributorIdentity,
            onDismiss = { editingIdentity = false },
            onSave = { name, email ->
                viewModel.saveContributorIdentity(name, email)
                editingIdentity = false
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.account_data_clear)) },
            text = { Text(stringResource(R.string.account_data_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearLogbookAndMemos()
                    confirmClear = false
                }) { Text(stringResource(R.string.account_data_clear_action), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CompactAppBar(
                title = stringResource(R.string.account_title),
                navigation = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = LocalDcColors.current.backgroundBase,
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.width(contentWidth).fillMaxSize().testTag(TestTags.MY_ACCOUNT_LIST),
                contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding, top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "account") {
                    AccountCard(
                        authState = state.authState,
                        member = state.member,
                        onLogin = { showLogin = true },
                        onLogout = authViewModel::logout,
                        onOpenProfile = onOpenMemberProfile,
                    )
                }
                state.contributorIdentity?.let { identity ->
                    item(key = "identity") {
                        ContributorIdentityCard(identity = identity, onEdit = {
                            editingIdentity = true
                        }, onForget = viewModel::clearContributorIdentity)
                    }
                }
                item(key = "outings") {
                    OutingsSummary(
                        outings = state.outings,
                        onOpen = { outing -> onOpenOuting(outing.canyonId, outing.id) },
                        onOpenAll = onOpenOutings,
                    )
                }
                item(key = "logbook") {
                    LogbookSummary(
                        state = state,
                        isConnected = state.authState is AuthState.Connected,
                        onOpenLogbook = onOpenLogbook,
                        onImportFromSite = viewModel::importFromSite,
                    )
                }
                item(key = "memos") { MemosSummary(state = state, onOpen = { onOpenLogbook(LogbookTab.CANYONS) }) }
                item(key = "contributions") {
                    AccountSection(stringResource(R.string.settings_section_contributions)) {
                        AccountRow(
                            icon = Icons.Default.CloudUpload,
                            title = stringResource(R.string.pending_debits_title),
                            subtitle = if (state.pendingDebitCount > 0) {
                                pluralStringResource(R.plurals.debit_pending_count, state.pendingDebitCount, state.pendingDebitCount)
                            } else {
                                stringResource(R.string.pending_debits_empty_title)
                            },
                            onClick = onOpenPendingDebits,
                        )
                    }
                }
                item(key = "follows") {
                    AccountSection(stringResource(R.string.account_section_follows)) {
                        val follows = state.follows
                        AccountRow(
                            icon = Icons.Default.Notifications,
                            title = stringResource(R.string.notifications_screen_title),
                            subtitle = if (follows.total == 0) {
                                stringResource(R.string.account_follows_none)
                            } else {
                                stringResource(
                                    R.string.account_follows_summary,
                                    follows.canyons,
                                    follows.forumCategories,
                                    follows.forumThreads,
                                    follows.members,
                                )
                            },
                            onClick = onOpenNotifications,
                        )
                    }
                }
                item(key = "data") {
                    AccountSection(stringResource(R.string.account_section_data)) {
                        AccountRow(
                            icon = Icons.Default.FileDownload,
                            title = stringResource(R.string.account_data_export_json),
                            subtitle = stringResource(R.string.account_data_export_json_hint),
                            onClick = { exportJson.launch("descente-canyon-${LocalDate.now()}.json") },
                        )
                        AccountRow(
                            icon = Icons.Default.TableChart,
                            title = stringResource(R.string.account_data_export_csv),
                            subtitle = stringResource(R.string.account_data_export_csv_hint),
                            onClick = { exportCsv.launch("descente-canyon-carnet-${LocalDate.now()}.csv") },
                        )
                        AccountRow(
                            icon = Icons.Default.FileUpload,
                            title = stringResource(R.string.account_data_import),
                            subtitle = stringResource(R.string.account_data_import_hint),
                            onClick = { importFile.launch(arrayOf(JSON_MIME, "text/plain", "application/octet-stream")) },
                        )
                        AccountRow(
                            icon = Icons.Default.DeleteOutline,
                            title = stringResource(R.string.account_data_clear),
                            onClick = { confirmClear = true },
                        )
                        Text(
                            stringResource(R.string.account_data_privacy),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalDcColors.current.textSecondary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                item(key = "settings") {
                    DcCard(modifier = Modifier.fillMaxWidth(), contentPadding = 8.dp) {
                        Box(Modifier.padding(horizontal = 8.dp)) {
                            AccountRow(
                                icon = Icons.Default.Settings,
                                title = stringResource(R.string.settings_title),
                                onClick = onOpenSettings,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountCard(
    authState: AuthState,
    member: ForumUser?,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val colors = LocalDcColors.current
    DcCard(modifier = Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        val username = when (authState) {
            is AuthState.Connected -> authState.username
            is AuthState.SessionExpired -> authState.username
            else -> null
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier.size(52.dp).background(colors.primaryAction.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (username != null) {
                    Text(
                        username.first().uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.primaryAction,
                    )
                } else {
                    Icon(
                        Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = colors.primaryAction,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = username ?: stringResource(R.string.account_disconnected_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = when (authState) {
                        is AuthState.Connected -> stringResource(R.string.account_connected_subtitle)
                        is AuthState.SessionExpired -> stringResource(R.string.account_session_expired)
                        else -> stringResource(R.string.account_disconnected_body)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (authState is AuthState.SessionExpired) MaterialTheme.colorScheme.error else colors.textSecondary,
                )
                if (member != null && authState is AuthState.Connected) {
                    Text(
                        listOf(
                            pluralStringResource(R.plurals.account_member_reports, member.debitReportCount, member.debitReportCount),
                            pluralStringResource(R.plurals.account_member_posts, member.forumPostCount, member.forumPostCount),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            when (authState) {
                is AuthState.Connected -> {
                    if (member != null) {
                        TextButton(onClick = { onOpenProfile(member.username) }) { Text(stringResource(R.string.account_open_profile)) }
                    }
                    OutlinedButton(onClick = onLogout) { Text(stringResource(R.string.account_logout)) }
                }

                is AuthState.SessionExpired -> Button(onClick = onLogin) { Text(stringResource(R.string.account_relogin)) }

                AuthState.Loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))

                else -> Button(onClick = onLogin) { Text(stringResource(R.string.debit_login_prompt_button)) }
            }
        }
    }
}

@Composable
private fun ContributorIdentityCard(identity: ContributorIdentity, onEdit: () -> Unit, onForget: () -> Unit) {
    AccountSection(stringResource(R.string.account_identity_title)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.Person, contentDescription = null, tint = LocalDcColors.current.textSecondary)
            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(identity.name, style = MaterialTheme.typography.bodyLarge)
                identity.email?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalDcColors.current.textSecondary) }
            }
        }
        Text(
            stringResource(R.string.account_identity_body),
            style = MaterialTheme.typography.bodySmall,
            color = LocalDcColors.current.textSecondary,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onForget) { Text(stringResource(R.string.account_identity_forget)) }
            TextButton(onClick = onEdit) { Text(stringResource(R.string.account_identity_edit)) }
        }
    }
}

/** The outings planned (the one under way first) and the way to all of them, the starting places and the stays. */
@Composable
private fun OutingsSummary(outings: List<PlannedOuting>, onOpen: (PlannedOuting) -> Unit, onOpenAll: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val zone = ZoneId.systemDefault()
    AccountSection(stringResource(R.string.outings_title), modifier = Modifier.testTag(TestTags.MY_ACCOUNT_OUTINGS)) {
        if (outings.isEmpty()) {
            Text(
                stringResource(R.string.outings_empty_title),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalDcColors.current.textSecondary,
            )
        }
        outings.take(SHOWN_OUTINGS).forEach { outing ->
            val start = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)
                .format((outing.departure ?: outing.start).atZone(zone))
            val back = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(outing.expectedBack.atZone(zone))
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(outing) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Default.Hiking, contentDescription = null, tint = LocalDcColors.current.primaryAction)
                Column(modifier = Modifier.weight(1f)) {
                    Text(outing.canyonName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.outing_active_summary, start, back), style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = LocalDcColors.current.textMuted)
            }
        }
        TextButton(onClick = onOpenAll) { Text(stringResource(R.string.account_outings_all)) }
    }
}

private const val SHOWN_OUTINGS = 3

@Composable
private fun LogbookSummary(
    state: MyAccountUiState,
    isConnected: Boolean,
    onOpenLogbook: (LogbookTab) -> Unit,
    onImportFromSite: () -> Unit,
) {
    val colors = LocalDcColors.current
    val formatter = rememberLogbookDateFormatter()
    AccountSection(stringResource(R.string.logbook_card_title), modifier = Modifier.testTag(TestTags.MY_ACCOUNT_LOGBOOK)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onOpenLogbook(LogbookTab.OUTINGS) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = colors.textSecondary)
            Text(
                listOf(
                    pluralStringResource(R.plurals.logbook_stats, state.stats.outings, state.stats.outings),
                    pluralStringResource(R.plurals.logbook_stats_canyons, state.stats.canyons, state.stats.canyons),
                    pluralStringResource(R.plurals.logbook_stats_year, state.stats.outingsThisYear, state.stats.outingsThisYear),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textMuted)
        }
        if (state.recentEntries.isEmpty()) {
            Text(stringResource(R.string.account_logbook_empty), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        } else {
            state.recentEntries.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenLogbook(LogbookTab.OUTINGS) }.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.canyonName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(entry.date.format(formatter), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onOpenLogbook(LogbookTab.BILAN) }) {
                Icon(Icons.Default.BarChart, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.logbook_tab_bilan), modifier = Modifier.padding(start = 6.dp))
            }
            TextButton(onClick = { onOpenLogbook(LogbookTab.OUTINGS) }) { Text(stringResource(R.string.account_see_all)) }
        }
        if (isConnected) {
            HorizontalDivider(color = colors.borderSubtle)
            AccountRow(
                icon = Icons.Default.CloudDownload,
                title = stringResource(R.string.account_import_site),
                subtitle = stringResource(
                    if (state.isImportingFromSite) R.string.account_import_site_running else R.string.account_import_site_hint,
                ),
                onClick = onImportFromSite.takeUnless { state.isImportingFromSite },
                trailing = {
                    if (state.isImportingFromSite) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                },
                modifier = Modifier.testTag(TestTags.MY_ACCOUNT_IMPORT_SITE),
            )
        }
    }
}

@Composable
private fun MemosSummary(state: MyAccountUiState, onOpen: () -> Unit) {
    val colors = LocalDcColors.current
    AccountSection(stringResource(R.string.account_section_memos)) {
        AccountRow(
            icon = Icons.AutoMirrored.Filled.StickyNote2,
            title = if (state.memoCount == 0) {
                stringResource(R.string.account_memos_none)
            } else {
                pluralStringResource(R.plurals.account_memo_count, state.memoCount, state.memoCount)
            },
            subtitle = stringResource(R.string.account_memos_hint).takeIf { state.memoCount == 0 },
            onClick = onOpen,
        )
        state.recentMemos.forEach { memo ->
            Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp)) {
                Text(memo.canyonName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    memo.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ContributorIdentityDialog(identity: ContributorIdentity?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(identity?.name.orEmpty()) }
    var email by rememberSaveable { mutableStateOf(identity?.email.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_identity_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_IDENTITY_LENGTH) },
                    label = { Text(stringResource(R.string.debit_observer_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.take(MAX_IDENTITY_LENGTH) },
                    label = { Text(stringResource(R.string.debit_observer_email)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, email) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AccountSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        DcCard(modifier = Modifier.fillMaxWidth(), contentPadding = 8.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 8.dp), content = content)
        }
    }
}

@Composable
private fun AccountRow(
    icon: ImageVector,
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalDcColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.textSecondary)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary) }
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textMuted)
        }
    }
}

private suspend fun ContentResolver.writeText(uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
    runCatching { openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null }.getOrDefault(false)
}

private suspend fun ContentResolver.readText(uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching { openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
}

private const val JSON_MIME = "application/json"
private const val CSV_MIME = "text/csv"
private const val MAX_IDENTITY_LENGTH = 120
