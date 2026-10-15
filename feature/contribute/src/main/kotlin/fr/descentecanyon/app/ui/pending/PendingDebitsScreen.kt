package fr.descentecanyon.app.ui.pending

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.ui.auth.AuthViewModel
import fr.descentecanyon.app.ui.auth.LoginDialog
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.components.debitLevelLabel
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcCardVariant
import fr.descentecanyon.app.ui.design.DcEmptyState
import fr.descentecanyon.app.ui.design.DcFlowBadge
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.design.rememberDcScreenHorizontalPadding
import fr.descentecanyon.app.ui.test.TestTags
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun PendingDebitsScreen(
    onBackClick: () -> Unit,
    onCanyonClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: PendingDebitsViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val authUiState by authViewModel.uiState.collectAsStateWithLifecycle()
    var showLoginDialog by rememberSaveable { mutableStateOf(false) }
    var itemToDelete by rememberSaveable { mutableStateOf<Long?>(null) }
    val contentWidth = rememberDcContentWidth()
    val horizontalPadding = rememberDcScreenHorizontalPadding()

    if (showLoginDialog) {
        LoginDialog(
            uiState = authUiState,
            onUsernameChanged = authViewModel::onUsernameChanged,
            onPasswordChanged = authViewModel::onPasswordChanged,
            onLogin = authViewModel::login,
            onLogout = authViewModel::logout,
            onDismiss = { showLoginDialog = false },
        )
    }
    LaunchedEffect(showLoginDialog, authUiState.authState) {
        if (showLoginDialog && authUiState.authState is AuthState.Connected) {
            showLoginDialog = false
        }
    }

    itemToDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text(stringResource(R.string.pending_debits_delete_title)) },
            text = { Text(stringResource(R.string.pending_debits_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(id)
                        itemToDelete = null
                    },
                    modifier = Modifier.testTag(TestTags.PENDING_DEBIT_DELETE_CONFIRM),
                ) {
                    Text(stringResource(R.string.pending_debits_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        containerColor = LocalDcColors.current.backgroundBase,
        topBar = {
            CompactAppBar(
                title = stringResource(R.string.pending_debits_title),
                navigation = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
            when {
                uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.padding(32.dp))
                uiState.items.isEmpty() -> DcEmptyState(
                    title = stringResource(R.string.pending_debits_empty_title),
                    body = stringResource(R.string.pending_debits_empty_body),
                    icon = Icons.Default.CloudDone,
                    modifier = Modifier
                        .padding(top = 48.dp)
                        .testTag(TestTags.PENDING_DEBITS_EMPTY),
                )
                else -> LazyColumn(
                    modifier = Modifier
                        .width(contentWidth)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = horizontalPadding,
                        end = horizontalPadding,
                        top = 16.dp,
                        bottom = contentPadding.calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        PendingStatusCard(
                            status = uiState.status,
                            onLoginClick = { showLoginDialog = true },
                        )
                    }
                    items(uiState.items, key = { it.id }) { item ->
                        PendingDebitCard(
                            item = item,
                            onCanyonClick = { onCanyonClick(item.canyonId) },
                            onDeleteClick = { itemToDelete = item.id },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingStatusCard(status: PendingDebitsStatus, onLoginClick: () -> Unit) {
    val (icon: ImageVector, text: String) = when (status) {
        PendingDebitsStatus.OFFLINE -> Icons.Default.CloudOff to stringResource(R.string.pending_debits_status_offline)
        PendingDebitsStatus.LOGIN_REQUIRED -> Icons.Default.Lock to stringResource(R.string.pending_debits_status_login)
        PendingDebitsStatus.SENDING, PendingDebitsStatus.EMPTY ->
            Icons.Default.CloudUpload to
                stringResource(R.string.pending_debits_status_sending)
    }
    DcCard(variant = if (status == PendingDebitsStatus.SENDING) DcCardVariant.Surface else DcCardVariant.Warning) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = LocalDcColors.current.textSecondary)
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        if (status == PendingDebitsStatus.LOGIN_REQUIRED) {
            Button(onClick = onLoginClick, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.debit_login_prompt_button))
            }
        }
    }
}

@Composable
private fun PendingDebitCard(item: PendingDebitItem, onCanyonClick: () -> Unit, onDeleteClick: () -> Unit) {
    val colors = LocalDcColors.current
    val date = item.observationDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    val type = when (item.observationType) {
        ObservationType.PARCOURU -> stringResource(R.string.debit_type_descended)
        ObservationType.NON_PARCOURU -> stringResource(R.string.debit_type_not_descended)
    }
    DcCard(onClick = onCanyonClick) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = item.canyonName ?: stringResource(R.string.canyon_fallback_title, item.canyonId),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = "$date · $type", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                DcFlowBadge(niveau = item.debitLevel, label = debitLevelLabel(item.debitLevel))
            }
            IconButton(onClick = onDeleteClick, modifier = Modifier.testTag(TestTags.pendingDebitDelete(item.id))) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.pending_debits_delete_confirm))
            }
        }
        if (item.comment.isNotBlank()) {
            Text(
                text = item.comment,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
        if (item.needsLogin) {
            Text(
                text = stringResource(R.string.pending_debits_with_account),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
