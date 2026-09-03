package fr.descentecanyon.app.ui.favorites

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.R
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

import fr.descentecanyon.app.domain.model.FavoriteFolder

enum class FavoriteSortOption {
    DATE_ADDED_DESC,
    DATE_ADDED_ASC,
    RATING_DESC,
    NAME_ASC,
}

data class FavoritesUiState(
    val rawFavorites: List<CanyonSummary> = emptyList(),
    val filteredFavorites: List<CanyonSummary> = emptyList(),
    val availableCountries: List<String> = emptyList(),
    val availableRegions: List<String> = emptyList(),
    val selectedCountry: String? = null,
    val selectedRegion: String? = null,
    val minRating: Float = 0f,
    val selectedSort: FavoriteSortOption = FavoriteSortOption.DATE_ADDED_DESC,
    val folders: List<FavoriteFolder> = emptyList(),
    val selectedFolderId: Int? = null,
    val canyonFolderMap: Map<Int, Set<Int>> = emptyMap(), // canyonId -> set of folderIds
    val isLoading: Boolean = false,
    val error: String? = null,
)

private data class FilterParams(
    val sort: FavoriteSortOption = FavoriteSortOption.DATE_ADDED_DESC,
    val country: String? = null,
    val region: String? = null,
    val minRating: Float = 0f,
    val folderId: Int? = null,
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val favoritesRepository: FavoritesRepository,
) : ViewModel() {

    private val _rawFavorites = MutableStateFlow<List<CanyonSummary>>(emptyList())
    private val _folders = MutableStateFlow<List<FavoriteFolder>>(emptyList())
    private val _canyonFolderMap = MutableStateFlow<Map<Int, Set<Int>>>(emptyMap())
    private val _filterParams = MutableStateFlow(FilterParams())
    private val _isLoading = MutableStateFlow(true)
    private val _error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<FavoritesUiState> = combine(
        _rawFavorites,
        _folders,
        _canyonFolderMap,
        _filterParams
    ) { rawList, folders, folderMap, params ->
        val availableCountries = rawList.map { it.pays }.distinct().sorted()
        val availableRegions = rawList.mapNotNull { it.region ?: it.departement }.distinct().sorted()

        var filtered = rawList.asSequence()

        if (params.folderId != null) {
            filtered = filtered.filter { summary ->
                folderMap[summary.id]?.contains(params.folderId) == true
            }
        }

        if (!params.country.isNullOrBlank()) {
            filtered = filtered.filter { it.pays.equals(params.country, ignoreCase = true) }
        }

        if (!params.region.isNullOrBlank()) {
            filtered = filtered.filter { 
                (it.region?.equals(params.region, ignoreCase = true) == true) ||
                (it.departement?.equals(params.region, ignoreCase = true) == true)
            }
        }

        if (params.minRating > 0f) {
            filtered = filtered.filter { (it.interet ?: 0f) >= params.minRating }
        }

        val sortedList = when (params.sort) {
            FavoriteSortOption.DATE_ADDED_DESC -> filtered.sortedByDescending { it.favoriteAddedAt ?: 0L }
            FavoriteSortOption.DATE_ADDED_ASC -> filtered.sortedBy { it.favoriteAddedAt ?: Long.MAX_VALUE }
            FavoriteSortOption.RATING_DESC -> filtered.sortedByDescending { it.interet ?: 0f }
            FavoriteSortOption.NAME_ASC -> filtered.sortedBy { it.nom }
        }.toList()

        FavoritesUiState(
            rawFavorites = rawList,
            filteredFavorites = sortedList,
            availableCountries = availableCountries,
            availableRegions = availableRegions,
            selectedCountry = params.country,
            selectedRegion = params.region,
            minRating = params.minRating,
            selectedSort = params.sort,
            folders = folders,
            selectedFolderId = params.folderId,
            canyonFolderMap = folderMap,
        )
    }.combine(_isLoading) { state, isLoading ->
        state.copy(isLoading = isLoading)
    }.combine(_error) { state, error ->
        state.copy(error = error)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = FavoritesUiState()
    )

    init {
        loadFavorites()
        loadFolders()
        loadCanyonFolderMap()
    }

    private fun loadFavorites() {
        viewModelScope.launch {
            _isLoading.value = true
            favoritesRepository.getFavorites().collect { favorites ->
                _rawFavorites.value = favorites
                _isLoading.value = false
                _error.value = null
            }
        }
    }

    private fun loadFolders() {
        viewModelScope.launch {
            favoritesRepository.getFolders().collect { foldersList ->
                _folders.value = foldersList
            }
        }
    }

    private fun loadCanyonFolderMap() {
        viewModelScope.launch {
            favoritesRepository.getAllCanyonFolderMap().collect { map ->
                _canyonFolderMap.value = map
            }
        }
    }

    fun selectFolder(folderId: Int?) {
        _filterParams.value = _filterParams.value.copy(folderId = folderId)
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching {
                favoritesRepository.createFolder(name)
            }.onFailure {
                _error.value = "Impossible de créer le dossier."
            }
        }
    }

    fun deleteFolder(folderId: Int) {
        viewModelScope.launch {
            runCatching {
                if (_filterParams.value.folderId == folderId) {
                    selectFolder(null)
                }
                favoritesRepository.deleteFolder(folderId)
            }.onFailure {
                _error.value = "Impossible de supprimer le dossier."
            }
        }
    }

    fun toggleCanyonInFolder(canyonId: Int, folderId: Int) {
        viewModelScope.launch {
            val currentFolderIds = _canyonFolderMap.value[canyonId] ?: emptySet()
            if (currentFolderIds.contains(folderId)) {
                favoritesRepository.removeCanyonFromFolder(canyonId, folderId)
            } else {
                favoritesRepository.addCanyonToFolder(canyonId, folderId)
            }
        }
    }

    fun setSortOption(option: FavoriteSortOption) {
        _filterParams.value = _filterParams.value.copy(sort = option)
    }

    fun setCountryFilter(country: String?) {
        _filterParams.value = _filterParams.value.copy(country = country)
    }

    fun setRegionFilter(region: String?) {
        _filterParams.value = _filterParams.value.copy(region = region)
    }

    fun setMinRatingFilter(minRating: Float) {
        _filterParams.value = _filterParams.value.copy(minRating = minRating)
    }

    fun resetFilters() {
        _filterParams.value = FilterParams()
    }

    fun removeFavorite(canyonId: Int) {
        viewModelScope.launch {
            runCatching {
                favoritesRepository.removeFavorite(canyonId)
            }.onFailure {
                _error.value = context.getString(R.string.favorite_remove_error)
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
