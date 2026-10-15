package fr.descentecanyon.app.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.ui.common.UiText
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Order of the favorites list. */
enum class FavoritesSort {
    /** Most recently added first, as stored. */
    ADDED,
    NAME,

    /** Freshest flow report first: which favorites run now. */
    LATEST_REPORT,
}

internal fun List<CanyonSummary>.sortedFor(sort: FavoritesSort): List<CanyonSummary> = when (sort) {
    FavoritesSort.ADDED -> this

    FavoritesSort.NAME -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.nom })

    FavoritesSort.LATEST_REPORT -> sortedWith(
        compareByDescending<CanyonSummary, java.time.LocalDate?>(nullsFirst()) {
            it.dernierDebitDate
        },
    )
}

data class FavoritesUiState(
    val favorites: List<CanyonSummary> = emptyList(),
    val sort: FavoritesSort = FavoritesSort.ADDED,
    val isLoading: Boolean = false,
    val error: UiText? = null,
    /** Favorite just removed by the user, offered for undo. */
    val removedFavorite: CanyonSummary? = null,
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val debitRepository: DebitRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()
    private var unsorted: List<CanyonSummary> = emptyList()

    init {
        loadFavorites()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun loadFavorites() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            favoritesRepository.getFavorites()
                .flatMapLatest { favorites ->
                    // Last report known on the device: tells at a glance which favorites run.
                    debitRepository.observeLatestKnownDebits(favorites.map { it.id }.toSet()).map { latest ->
                        favorites.map { canyon ->
                            latest[canyon.id]?.let { debit -> canyon.copy(dernierDebit = debit.niveau, dernierDebitDate = debit.date) }
                                ?: canyon
                        }
                    }
                }
                .collect { favorites ->
                    unsorted = favorites
                    _uiState.update { it.copy(favorites = favorites.sortedFor(it.sort), isLoading = false, error = null) }
                }
        }
    }

    fun selectSort(sort: FavoritesSort) {
        _uiState.update { it.copy(sort = sort, favorites = unsorted.sortedFor(sort)) }
    }

    fun removeFavorite(canyon: CanyonSummary) {
        viewModelScope.launch {
            suspendRunCatching { favoritesRepository.removeFavorite(canyon.id) }
                .onSuccess { _uiState.update { it.copy(removedFavorite = canyon) } }
                .onFailure { _uiState.update { it.copy(error = UiText.res(R.string.favorite_remove_error)) } }
        }
    }

    fun undoRemoveFavorite() {
        val removed = _uiState.value.removedFavorite ?: return
        _uiState.update { it.copy(removedFavorite = null) }
        viewModelScope.launch { suspendRunCatching { favoritesRepository.addFavorite(removed.id) } }
    }

    fun onRemovedFavoriteShown() {
        _uiState.update { it.copy(removedFavorite = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
