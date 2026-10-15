package fr.descentecanyon.app.ui.outing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.model.EmergencyNumber
import fr.descentecanyon.app.domain.model.EmergencyNumbers
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.usecase.GetCanyonPreviewUseCase
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EmergencyUiState(
    val canyonName: String? = null,
    val numbers: List<EmergencyNumber> = listOf(EmergencyNumbers.EUROPEAN),
    /** False outside the countries whose numbers are known: the local number must be checked. */
    val isCountryKnown: Boolean = true,
    /** Escape routes first, then the other points of the sheet. */
    val points: List<GeoPoint> = emptyList(),
)

/** Emergency numbers of the canyon's country and its points; everything comes from the offline catalog. */
@HiltViewModel
class EmergencyViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getCanyonPreviewUseCase: GetCanyonPreviewUseCase,
) : ViewModel() {

    private val canyonId: Int = savedStateHandle["canyonId"] ?: 0

    private val _uiState = MutableStateFlow(EmergencyUiState())
    val uiState: StateFlow<EmergencyUiState> = _uiState.asStateFlow()

    init {
        if (canyonId > 0) {
            viewModelScope.launch {
                getCanyonPreviewUseCase(canyonId).onSuccess { detail ->
                    _uiState.update {
                        it.copy(
                            canyonName = detail.canyon.nom,
                            numbers = EmergencyNumbers.forCountries(detail.canyon.pays),
                            isCountryKnown = EmergencyNumbers.isKnown(detail.canyon.pays),
                            points = detail.geoPoints.sortedBy { point -> pointRank(point.type) },
                        )
                    }
                }
            }
        }
    }

    private fun pointRank(type: GeoPointType): Int = POINT_ORDER.indexOf(type).takeIf { it >= 0 } ?: POINT_ORDER.size

    private companion object {
        val POINT_ORDER = listOf(
            GeoPointType.ECHAPPATOIRE,
            GeoPointType.SORTIE,
            GeoPointType.ENTREE,
            GeoPointType.PARKING_AVAL,
            GeoPointType.PARKING_AMONT,
            GeoPointType.POINT_REMARQUABLE,
        )
    }
}
