package fr.descentecanyon.app.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.ui.common.UiText
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FavoritesUiState(val favorites: List<CanyonSummary> = emptyList(), val isLoading: Boolean = false, val error: UiText? = null)

@HiltViewModel
class FavoritesViewModel @Inject constructor(private val favoritesRepository: FavoritesRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    init {
        loadFavorites()
    }

    private fun loadFavorites() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            favoritesRepository.getFavorites().collect { favorites ->
                _uiState.update {
                    it.copy(
                        favorites = favorites,
                        isLoading = false,
                        error = null,
                    )
                }
            }
        }
    }

    fun removeFavorite(canyonId: Int) {
        viewModelScope.launch {
            suspendRunCatching {
                favoritesRepository.removeFavorite(canyonId)
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(error = UiText.res(R.string.favorite_remove_error))
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
