package fr.descentecanyon.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.ServerMessage
import fr.descentecanyon.app.domain.model.ServerMessageLevel
import fr.descentecanyon.app.domain.repository.AppServerRepository
import fr.descentecanyon.app.ui.common.openUrl
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcCardVariant
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ServerNoticesState(val updateRequired: Boolean = false, val messages: List<ServerMessage> = emptyList()) {
    val isEmpty: Boolean get() = !updateRequired && messages.isEmpty()
}

@HiltViewModel
class ServerNoticesViewModel @Inject constructor(private val repository: AppServerRepository) : ViewModel() {

    val state: StateFlow<ServerNoticesState> = repository.observeState()
        .map { server ->
            if (server.isActive) ServerNoticesState(server.updateRequired, server.messages) else ServerNoticesState()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ServerNoticesState())

    fun dismiss(message: ServerMessage) {
        viewModelScope.launch { repository.dismissMessage(message.id) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Messages of the app's server (incident on the site, maintenance...) and the "new version needed" notice. */
@Composable
internal fun HomeServerNotices(state: ServerNoticesState, onDismiss: (ServerMessage) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.updateRequired) {
            DcCard(variant = DcCardVariant.Warning, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null)
                    Text(
                        stringResource(R.string.server_update_required),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { context.openUrl("https://play.google.com/store/apps/details?id=${context.packageName}") }) {
                        Text(stringResource(R.string.server_update_open))
                    }
                }
            }
        }
        state.messages.forEach { message ->
            DcCard(
                variant = if (message.level == ServerMessageLevel.WARNING) DcCardVariant.Warning else DcCardVariant.Surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        if (message.level == ServerMessageLevel.WARNING) Icons.Default.WarningAmber else Icons.Default.Info,
                        contentDescription = null,
                    )
                    Text(message.textFor(language), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onDismiss(message) }) {
                        Text(stringResource(R.string.server_message_hide))
                    }
                }
            }
        }
    }
}
