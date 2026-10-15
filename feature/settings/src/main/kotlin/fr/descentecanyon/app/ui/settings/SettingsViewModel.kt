package fr.descentecanyon.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.ThemeMode
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.domain.repository.PreferencesRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(val themeMode: ThemeMode = ThemeMode.SYSTEM, val pendingDebitCount: Int = 0, val catalogSize: Int? = null)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    debitSubmissionRepository: DebitSubmissionRepository,
    canyonRepository: CanyonRepository,
) : ViewModel() {

    private val catalogSize = flow { emit(suspendRunCatching { canyonRepository.getCatalogSize() }.getOrNull()) }

    val uiState: StateFlow<SettingsUiState> = combine(
        preferencesRepository.observeThemeMode(),
        debitSubmissionRepository.observePendingCount(),
        catalogSize,
    ) { themeMode, pendingCount, size ->
        SettingsUiState(themeMode = themeMode, pendingDebitCount = pendingCount, catalogSize = size)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = SettingsUiState(themeMode = preferencesRepository.getThemeMode()),
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferencesRepository.setThemeMode(mode) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
