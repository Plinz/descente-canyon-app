package fr.descentecanyon.app.ui.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.MapPosition
import fr.descentecanyon.app.domain.model.ReportZone
import fr.descentecanyon.app.domain.model.ZoneCountry
import fr.descentecanyon.app.domain.model.buildZoneTree
import fr.descentecanyon.app.domain.model.subdivisionsFor
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.PreferencesRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WelcomeUiState(
    /** The countries of the catalog, each with its subdivisions. */
    val zones: List<ZoneCountry> = emptyList(),
    val selectedCountry: String? = null,
    val selectedSubdivision: String? = null,
)

/**
 * The area chosen at the first opening: the latest reports of the home screen are filtered on it and
 * the map opens over its canyons. No area chosen leaves both as they are (everywhere).
 */
@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val canyonRepository: CanyonRepository,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WelcomeUiState())
    val uiState: StateFlow<WelcomeUiState> = _uiState.asStateFlow()
    private var catalog: List<CanyonSearchItem> = emptyList()

    init {
        viewModelScope.launch {
            catalog = canyonRepository.observeSearchCatalog().first()
            _uiState.update { it.copy(zones = buildZoneTree(catalog)) }
        }
    }

    fun selectZone(country: String?, subdivision: String?) {
        _uiState.update { it.copy(selectedCountry = country, selectedSubdivision = subdivision?.takeIf { country != null }) }
    }

    /** Keeps the area, then [onDone]. */
    fun finish(onDone: () -> Unit) {
        val state = _uiState.value
        val country = state.selectedCountry
        viewModelScope.launch {
            if (country != null) {
                preferencesRepository.setLatestReportsZone(ReportZone(country = country, department = state.selectedSubdivision))
                mapPositionOf(country, state.selectedSubdivision)?.let(preferencesRepository::setLastMapPosition)
            }
            onDone()
        }
    }

    /** Over the canyons of the area: their middle, closer for a subdivision than for a country. */
    private fun mapPositionOf(country: String, subdivision: String?): MapPosition? {
        val points = catalog
            .filter { item -> country in item.countryTokens && (subdivision == null || subdivision in item.subdivisionsFor(country)) }
            .mapNotNull { item -> item.representativeLat?.let { lat -> item.representativeLng?.let { lng -> lat to lng } } }
        if (points.isEmpty()) return null
        return MapPosition(
            latitude = points.map { it.first }.average(),
            longitude = points.map { it.second }.average(),
            zoom = if (subdivision != null) SUBDIVISION_ZOOM else COUNTRY_ZOOM,
        )
    }

    private companion object {
        const val SUBDIVISION_ZOOM = 8.5
        const val COUNTRY_ZOOM = 5.5
    }
}
