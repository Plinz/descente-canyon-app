package fr.descentecanyon.app.ui.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.FeedbackKind
import fr.descentecanyon.app.domain.model.FeedbackLimits
import fr.descentecanyon.app.domain.model.FeedbackMessage
import fr.descentecanyon.app.domain.model.FeedbackStatus
import fr.descentecanyon.app.ui.common.asString
import fr.descentecanyon.app.ui.common.labelRes
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.test.TestTags
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The idea box: a bug, an idea or a change in a few lines, the technical details and the account
 * joined by default, then the messages sent from this phone with what became of them and the replies.
 */
@Composable
fun FeedbackScreen(onBackClick: () -> Unit, modifier: Modifier = Modifier, viewModel: FeedbackViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)
    var deleting by remember { mutableStateOf<FeedbackMessage?>(null) }

    val message = state.message?.asString()
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = {
                Text(stringResource(if (target.isQueued) R.string.feedback_delete_queued_confirm else R.string.feedback_delete_confirm))
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.delete(target)
                }) { Text(stringResource(R.string.feedback_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CompactAppBar(
                title = stringResource(R.string.feedback_title),
                navigation = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.width(contentWidth).fillMaxSize().testTag(TestTags.FEEDBACK_LIST),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "intro") {
                    Text(
                        stringResource(R.string.feedback_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalDcColors.current.textSecondary,
                    )
                }
                item(key = "form") {
                    FeedbackForm(
                        state = state,
                        onKind = viewModel::selectKind,
                        onText = viewModel::onTextChanged,
                        onAttachTechnical = viewModel::setAttachTechnical,
                        onAttachContact = viewModel::setAttachContact,
                        onSend = viewModel::send,
                    )
                }
                if (state.messages.isNotEmpty()) {
                    item(key = "mine") {
                        Text(
                            stringResource(R.string.feedback_mine_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(state.messages, key = { it.id }) { feedback ->
                        FeedbackMessageCard(feedback = feedback, onDelete = { deleting = feedback })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedbackForm(
    state: FeedbackUiState,
    onKind: (FeedbackKind) -> Unit,
    onText: (String) -> Unit,
    onAttachTechnical: (Boolean) -> Unit,
    onAttachContact: (Boolean) -> Unit,
    onSend: () -> Unit,
) {
    val colors = LocalDcColors.current
    DcCard(modifier = Modifier.fillMaxWidth(), contentPadding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeedbackKind.entries.forEach { kind ->
                    FilterChip(
                        selected = state.kind == kind,
                        onClick = { onKind(kind) },
                        label = { Text(stringResource(kind.labelRes())) },
                        leadingIcon = { Icon(kind.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.testTag(TestTags.feedbackKind(kind.name)),
                    )
                }
            }
            val tooLong = state.length > FeedbackLimits.TEXT_MAX
            OutlinedTextField(
                value = state.text,
                onValueChange = onText,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.FEEDBACK_TEXT),
                label = { Text(stringResource(R.string.feedback_text_label)) },
                placeholder = { Text(stringResource(state.kind.hintRes())) },
                minLines = 5,
                maxLines = 12,
                isError = tooLong,
                supportingText = {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.feedback_private_hint),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.feedback_counter, state.length, FeedbackLimits.TEXT_MAX),
                            color = if (tooLong) MaterialTheme.colorScheme.error else colors.textMuted,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                },
            )
            CheckRow(
                checked = state.attachTechnical,
                onChange = onAttachTechnical,
                title = stringResource(R.string.feedback_attach_technical),
                detail = state.technical?.let { info ->
                    stringResource(R.string.feedback_technical_summary, info.appVersion, info.android, info.device, info.language)
                },
                modifier = Modifier.testTag(TestTags.FEEDBACK_ATTACH_TECHNICAL),
            )
            val contact = state.contact
            when {
                state.isLoadingContact -> Unit

                contact != null -> CheckRow(
                    checked = state.attachContact,
                    onChange = onAttachContact,
                    title = stringResource(R.string.feedback_attach_contact),
                    detail = listOf(
                        contact.email?.let { "${contact.username} · $it" }
                            ?: stringResource(R.string.feedback_contact_no_email, contact.username),
                        stringResource(R.string.feedback_contact_hint),
                    ).joinToString("\n"),
                    modifier = Modifier.testTag(TestTags.FEEDBACK_ATTACH_CONTACT),
                )

                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = colors.textMuted,
                        modifier = Modifier.padding(start = 12.dp).size(18.dp),
                    )
                    Text(
                        stringResource(R.string.feedback_anonymous),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
            if (!state.isAvailable) {
                Text(
                    stringResource(R.string.feedback_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = onSend,
                enabled = state.canSend,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.FEEDBACK_SEND),
            ) {
                if (state.isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.feedback_send))
                }
            }
            if (state.kind == null && state.text.isNotBlank()) {
                Text(stringResource(R.string.feedback_choose_kind), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
    }
}

/** A box ticked by default, its line explaining what goes with the message. */
@Composable
private fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, title: String, detail: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onChange(!checked) },
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f).padding(top = 10.dp, bottom = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = LocalDcColors.current.textSecondary)
            }
        }
    }
}

@Composable
private fun FeedbackMessageCard(feedback: FeedbackMessage, onDelete: () -> Unit) {
    val colors = LocalDcColors.current
    val formatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    var expanded by rememberSaveable(feedback.id) { mutableStateOf(false) }
    fun day(instant: java.time.Instant) = instant.atZone(ZoneId.systemDefault()).toLocalDate().format(formatter)
    DcCard(modifier = Modifier.fillMaxWidth().testTag(TestTags.feedbackMessage(feedback.id)), contentPadding = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(feedback.kind.icon(), contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                Text(
                    listOf(
                        stringResource(feedback.kind.labelRes()),
                        day(feedback.createdAt),
                        stringResource(if (feedback.isQueued) R.string.feedback_queued_label else feedback.status.labelRes()),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (feedback.isQueued) colors.warning else feedback.status.color(),
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                if (feedback.isQueued) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = colors.warning,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = stringResource(R.string.feedback_delete),
                        tint = colors.textMuted,
                    )
                }
            }
            Text(
                feedback.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            )
            feedback.reply?.let { reply ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = colors.primaryAction.copy(alpha = 0.10f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            feedback.repliedAt?.let { stringResource(R.string.feedback_reply_label, day(it)) }
                                ?: stringResource(R.string.feedback_reply_label_undated),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.primaryAction,
                        )
                        Text(reply, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedbackStatus.color() = when (this) {
    FeedbackStatus.DONE -> LocalDcColors.current.riskLow
    FeedbackStatus.DECLINED -> LocalDcColors.current.textMuted
    FeedbackStatus.PLANNED -> LocalDcColors.current.primaryAction
    FeedbackStatus.NEW, FeedbackStatus.READ -> LocalDcColors.current.textSecondary
}

private fun FeedbackKind.icon(): ImageVector = when (this) {
    FeedbackKind.BUG -> Icons.Default.BugReport
    FeedbackKind.IDEA -> Icons.Default.Lightbulb
    FeedbackKind.CHANGE -> Icons.Default.AutoFixHigh
}

private fun FeedbackKind?.hintRes(): Int = when (this) {
    FeedbackKind.BUG -> R.string.feedback_hint_bug
    FeedbackKind.IDEA -> R.string.feedback_hint_idea
    FeedbackKind.CHANGE -> R.string.feedback_hint_change
    null -> R.string.feedback_hint_none
}

private const val COLLAPSED_LINES = 4
