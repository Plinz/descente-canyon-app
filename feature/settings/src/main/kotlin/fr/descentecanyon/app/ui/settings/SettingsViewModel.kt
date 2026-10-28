package fr.descentecanyon.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.ThemeMode
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.PreferencesRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(val themeMode: ThemeMode = ThemeMode.SYSTEM, val catalogSize: Int? = null)

@HiltViewModel
class SettingsViewModel @Inject constructor(private val preferencesRepository: PreferencesRepository, canyonRepository: CanyonRepository) :
    ViewModel() {

    private val catalogSize = flow { emit(suspendRunCatching { canyonRepository.getCatalogSize() }.getOrNull()) }

    val uiState: StateFlow<SettingsUiState> = combine(preferencesRepository.observeThemeMode(), catalogSize) { themeMode, size ->
        SettingsUiState(themeMode = themeMode, catalogSize = size)
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
