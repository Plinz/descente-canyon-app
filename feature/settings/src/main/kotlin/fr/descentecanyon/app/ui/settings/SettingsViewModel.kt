package fr.descentecanyon.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.AppServerState
import fr.descentecanyon.app.domain.model.ThemeMode
import fr.descentecanyon.app.domain.repository.AppServerRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.PreferencesRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val catalogSize: Int? = null,
    val server: AppServerState = AppServerState(),
    val isCheckingServer: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    canyonRepository: CanyonRepository,
    private val appServerRepository: AppServerRepository,
) : ViewModel() {

    private val catalogSize = flow { emit(suspendRunCatching { canyonRepository.getCatalogSize() }.getOrNull()) }
    private val checkingServer = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        preferencesRepository.observeThemeMode(),
        catalogSize,
        appServerRepository.observeState(),
        checkingServer,
    ) { themeMode, size, server, checking ->
        SettingsUiState(themeMode = themeMode, catalogSize = size, server = server, isCheckingServer = checking)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = SettingsUiState(themeMode = preferencesRepository.getThemeMode()),
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferencesRepository.setThemeMode(mode) }
    }

    fun setServerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appServerRepository.setEnabled(enabled)
            if (enabled) checkServerNow()
        }
    }

    fun setCatalogOnMobileData(enabled: Boolean) {
        viewModelScope.launch { appServerRepository.setCatalogOnMobileData(enabled) }
    }

    fun checkServerNow() {
        if (checkingServer.value) return
        checkingServer.value = true
        viewModelScope.launch {
            try {
                appServerRepository.refresh()
            } finally {
                checkingServer.value = false
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
