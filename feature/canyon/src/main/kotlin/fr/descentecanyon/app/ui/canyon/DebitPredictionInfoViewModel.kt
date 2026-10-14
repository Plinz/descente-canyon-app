package fr.descentecanyon.app.ui.canyon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.DebitPredictionInfoSummary
import fr.descentecanyon.app.domain.repository.DebitPredictionRepository
import fr.descentecanyon.app.ui.common.UiText
import fr.descentecanyon.app.ui.common.toUiText
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DebitPredictionInfoUiState(
    val isLoading: Boolean = true,
    val summary: DebitPredictionInfoSummary? = null,
    val error: UiText? = null,
)

@HiltViewModel
class DebitPredictionInfoViewModel @Inject constructor(private val debitPredictionRepository: DebitPredictionRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DebitPredictionInfoUiState())
    val uiState: StateFlow<DebitPredictionInfoUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            debitPredictionRepository.getModelSummary()
                .onSuccess { summary ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            summary = summary,
                            error = null,
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            summary = null,
                            error = throwable.toUiText(fallback = R.string.prediction_info_unavailable),
                        )
                    }
                }
        }
    }
}
