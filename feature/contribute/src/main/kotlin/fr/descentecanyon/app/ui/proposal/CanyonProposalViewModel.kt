package fr.descentecanyon.app.ui.proposal

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.AppCanyons
import fr.descentecanyon.app.domain.model.CanyonPlace
import fr.descentecanyon.app.domain.model.CanyonProposal
import fr.descentecanyon.app.domain.model.CanyonSheet
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.NearbyCanyon
import fr.descentecanyon.app.domain.model.SheetPoint
import fr.descentecanyon.app.domain.model.SheetProblem
import fr.descentecanyon.app.domain.repository.CanyonProposalRepository
import fr.descentecanyon.app.ui.common.UiText
import fr.descentecanyon.app.ui.common.toUiText
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The text fields of a sheet, in the order of the form. */
enum class SheetField(val isText: Boolean = false, val isNumber: Boolean = false) {
    NOM,
    NOM_COMPLET,
    COURS_EAU,
    PAYS,
    REGION,
    DEPARTEMENT,
    COMMUNE,
    MASSIF,
    BASSIN,
    ALTITUDE_DEPART(isNumber = true),
    DENIVELE(isNumber = true),
    LONGUEUR(isNumber = true),
    CASCADE_MAX(isNumber = true),
    CORDE_MIN(isNumber = true),
    TEMPS_APPROCHE,
    TEMPS_DESCENTE,
    TEMPS_RETOUR,
    NAVETTE_KM,
    ACCES_AVAL(isText = true),
    ACCES_AMONT(isText = true),
    APPROCHE(isText = true),
    DESCENTE(isText = true),
    RETOUR(isText = true),
    ENGAGEMENT(isText = true),
    PERIODE(isText = true),
    GEOLOGIE(isText = true),
    HISTORIQUE(isText = true),
    REMARQUES(isText = true),
}

/** The sheet being written: every field as typed (numbers too), turned into a [CanyonSheet] to send. */
data class SheetDraft(
    val values: Map<SheetField, String> = emptyMap(),
    val vertical: Int? = null,
    val aquatic: Int? = null,
    val engagement: Int? = null,
    val isForbidden: Boolean = false,
    val points: List<SheetPoint> = emptyList(),
) {
    operator fun get(field: SheetField): String = values[field].orEmpty()

    fun with(field: SheetField, value: String): SheetDraft = copy(values = values + (field to value))

    /** The point the place comes from: the entry, else the first point. */
    val anchor: SheetPoint? get() = CanyonSheet.anchorOf(points)

    fun toSheet(): CanyonSheet = CanyonSheet(
        nom = this[SheetField.NOM].trim(),
        nomComplet = this[SheetField.NOM_COMPLET].trim(),
        pays = this[SheetField.PAYS].trim(),
        region = this[SheetField.REGION].trim(),
        departement = this[SheetField.DEPARTEMENT].trim(),
        commune = this[SheetField.COMMUNE].trim(),
        massif = this[SheetField.MASSIF].trim(),
        bassin = this[SheetField.BASSIN].trim(),
        coursEau = this[SheetField.COURS_EAU].trim(),
        cotation = if (vertical != null && aquatic != null && engagement != null) {
            CotationRating(raw = "", vertical = vertical, aquatic = aquatic, engagement = engagement)
        } else {
            null
        },
        altitudeDepart = number(SheetField.ALTITUDE_DEPART),
        denivele = number(SheetField.DENIVELE),
        longueur = number(SheetField.LONGUEUR),
        cascadeMax = number(SheetField.CASCADE_MAX),
        cordeMin = number(SheetField.CORDE_MIN),
        tempsApproche = this[SheetField.TEMPS_APPROCHE].trim(),
        tempsDescente = this[SheetField.TEMPS_DESCENTE].trim(),
        tempsRetour = this[SheetField.TEMPS_RETOUR].trim(),
        navette = CanyonSheet.shuttleOf(this[SheetField.NAVETTE_KM]),
        accesAval = this[SheetField.ACCES_AVAL].trim(),
        accesAmont = this[SheetField.ACCES_AMONT].trim(),
        approche = this[SheetField.APPROCHE].trim(),
        descente = this[SheetField.DESCENTE].trim(),
        retour = this[SheetField.RETOUR].trim(),
        engagement = this[SheetField.ENGAGEMENT].trim(),
        periode = this[SheetField.PERIODE].trim(),
        geologie = this[SheetField.GEOLOGIE].trim(),
        historique = this[SheetField.HISTORIQUE].trim(),
        remarques = this[SheetField.REMARQUES].trim(),
        isForbidden = isForbidden,
        points = points,
    )

    /** A number typed that is not one stays out of the sheet: [problems] reports it. */
    private fun number(field: SheetField): Int? = this[field].trim().toIntOrNull()

    /** The problems of the sheet, plus the numbers typed that are not whole numbers. */
    fun problems(): Set<SheetProblem> {
        val badNumber = SheetField.entries.any { it.isNumber && this[it].isNotBlank() && this[it].trim().toIntOrNull() == null }
        val badShuttle = this[SheetField.NAVETTE_KM].isNotBlank() && CanyonSheet.shuttleOf(this[SheetField.NAVETTE_KM]).isEmpty()
        return toSheet().problems() + listOfNotNull(SheetProblem.NUMBER.takeIf { badNumber }, SheetProblem.SHUTTLE.takeIf { badShuttle })
    }

    companion object {
        fun of(sheet: CanyonSheet): SheetDraft = SheetDraft(
            values = mapOf(
                SheetField.NOM to sheet.nom,
                SheetField.NOM_COMPLET to sheet.nomComplet,
                SheetField.COURS_EAU to sheet.coursEau,
                SheetField.PAYS to sheet.pays,
                SheetField.REGION to sheet.region,
                SheetField.DEPARTEMENT to sheet.departement,
                SheetField.COMMUNE to sheet.commune,
                SheetField.MASSIF to sheet.massif,
                SheetField.BASSIN to sheet.bassin,
                SheetField.ALTITUDE_DEPART to sheet.altitudeDepart?.toString().orEmpty(),
                SheetField.DENIVELE to sheet.denivele?.toString().orEmpty(),
                SheetField.LONGUEUR to sheet.longueur?.toString().orEmpty(),
                SheetField.CASCADE_MAX to sheet.cascadeMax?.toString().orEmpty(),
                SheetField.CORDE_MIN to sheet.cordeMin?.toString().orEmpty(),
                SheetField.TEMPS_APPROCHE to sheet.tempsApproche,
                SheetField.TEMPS_DESCENTE to sheet.tempsDescente,
                SheetField.TEMPS_RETOUR to sheet.tempsRetour,
                SheetField.NAVETTE_KM to CanyonSheet.kilometresOf(sheet.navette),
                SheetField.ACCES_AVAL to sheet.accesAval,
                SheetField.ACCES_AMONT to sheet.accesAmont,
                SheetField.APPROCHE to sheet.approche,
                SheetField.DESCENTE to sheet.descente,
                SheetField.RETOUR to sheet.retour,
                SheetField.ENGAGEMENT to sheet.engagement,
                SheetField.PERIODE to sheet.periode,
                SheetField.GEOLOGIE to sheet.geologie,
                SheetField.HISTORIQUE to sheet.historique,
                SheetField.REMARQUES to sheet.remarques,
            ),
            vertical = sheet.cotation?.vertical,
            aquatic = sheet.cotation?.aquatic,
            engagement = sheet.cotation?.engagement,
            isForbidden = sheet.isForbidden,
            points = sheet.points,
        )
    }
}

data class CanyonProposalUiState(
    /** The canyon added in the app whose change is proposed; null for a new canyon. */
    val canyonId: Int? = null,
    val isAvailable: Boolean = true,
    val isLoading: Boolean = false,
    val loadError: UiText? = null,
    val draft: SheetDraft = SheetDraft(),
    /** The sheet as it was when a change started: sending it unchanged makes no sense. */
    val original: SheetDraft? = null,
    val licenceAccepted: Boolean = false,
    /** Problems are shown once the user tried to send. */
    val showProblems: Boolean = false,
    val problems: Set<SheetProblem> = emptySet(),
    /** Catalog canyons near the point that places the canyon: it may exist already. */
    val nearby: List<NearbyCanyon> = emptyList(),
    /** The place fields last filled from the point: they follow it while nobody changes them. */
    val placeFromPoint: Map<SheetField, String> = emptyMap(),
    val isSending: Boolean = false,
    val sent: CanyonProposal? = null,
    val message: UiText? = null,
) {
    val isEdit: Boolean get() = canyonId != null
    val isUnchanged: Boolean get() = original != null && original.toSheet() == draft.toSheet()
}

/** Writes a new canyon, or a change to a canyon added in the app, and sends it for review. */
@HiltViewModel
class CanyonProposalViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val repository: CanyonProposalRepository) :
    ViewModel() {

    private val canyonId: Int? = savedStateHandle.get<Int>("canyonId")?.takeIf { AppCanyons.isAppCanyon(it) }

    /** A new canyon searched for by this name in vain: its name is written already. */
    private val searchedName: String? = savedStateHandle.get<String>("name")?.trim()?.takeIf { it.isNotEmpty() && canyonId == null }
    private val _uiState = MutableStateFlow(
        CanyonProposalUiState(
            canyonId = canyonId,
            draft = searchedName?.let { SheetDraft().with(SheetField.NOM, it) } ?: SheetDraft(),
        ),
    )
    val uiState: StateFlow<CanyonProposalUiState> = _uiState.asStateFlow()
    private var placeJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observeAvailable().collect { available -> _uiState.update { it.copy(isAvailable = available) } }
        }
        if (canyonId != null) load(canyonId)
    }

    private fun load(id: Int) {
        _uiState.update { it.copy(isLoading = true, loadError = null) }
        viewModelScope.launch {
            repository.sheetOf(id).fold(
                onSuccess = { sheet ->
                    val draft = SheetDraft.of(sheet)
                    _uiState.update { it.copy(isLoading = false, draft = draft, original = draft) }
                    // The place of a published sheet was reviewed: it changes only if its point does.
                    onAnchorMoved(draft.anchor, fillPlace = false)
                },
                onFailure = { error -> _uiState.update { it.copy(isLoading = false, loadError = error.toUiText()) } },
            )
        }
    }

    fun update(field: SheetField, value: String) = edit { it.with(field, value) }

    fun setCotation(vertical: Int?, aquatic: Int?, engagement: Int?) = edit {
        it.copy(vertical = vertical, aquatic = aquatic, engagement = engagement)
    }

    fun setForbidden(forbidden: Boolean) = edit { it.copy(isForbidden = forbidden) }

    fun acceptLicence(accepted: Boolean) = _uiState.update { it.copy(licenceAccepted = accepted) }

    /** Puts [point] at [index], or adds it (null index). The place follows the point that places the canyon. */
    fun savePoint(index: Int?, point: SheetPoint) {
        edit { draft ->
            val points = draft.points.toMutableList()
            if (index != null &&
                index in points.indices
            ) {
                points[index] = point
            } else if (points.size < CanyonSheet.MAX_POINTS) {
                points += point
            }
            draft.copy(points = points)
        }
    }

    fun removePoint(index: Int) = edit { draft -> draft.copy(points = draft.points.filterIndexed { i, _ -> i != index }) }

    fun send() {
        val state = _uiState.value
        if (state.isSending || state.sent != null) return
        val problems = state.draft.problems()
        if (problems.isNotEmpty() || !state.licenceAccepted || state.isUnchanged) {
            _uiState.update {
                it.copy(
                    showProblems = true,
                    problems = problems,
                    message = when {
                        problems.isNotEmpty() -> UiText.res(R.string.proposal_fix_problems)
                        !state.licenceAccepted -> UiText.res(R.string.proposal_licence_required)
                        else -> UiText.res(R.string.proposal_error_no_change)
                    },
                )
            }
            return
        }
        _uiState.update { it.copy(isSending = true) }
        viewModelScope.launch {
            repository.propose(state.draft.toSheet(), state.canyonId).fold(
                onSuccess = { proposal -> _uiState.update { it.copy(isSending = false, sent = proposal) } },
                onFailure = { error ->
                    _uiState.update { it.copy(isSending = false, message = error.toUiText(R.string.proposal_send_error)) }
                },
            )
        }
    }

    fun messageShown() = _uiState.update { it.copy(message = null) }

    private fun edit(change: (SheetDraft) -> SheetDraft) {
        val before = _uiState.value.draft.anchor
        _uiState.update { state ->
            val draft = change(state.draft)
            state.copy(draft = draft, problems = if (state.showProblems) draft.problems() else state.problems)
        }
        val anchor = _uiState.value.draft.anchor
        if (anchor != before) onAnchorMoved(anchor)
    }

    /** The place and the canyons nearby follow the point that places the canyon. */
    private fun onAnchorMoved(anchor: SheetPoint?, fillPlace: Boolean = true) {
        placeJob?.cancel()
        placeJob = viewModelScope.launch {
            val place = anchor?.takeIf { fillPlace }?.let { repository.placeNear(it.latitude, it.longitude) }
            val nearby = anchor?.let { point ->
                repository.canyonsNear(point.latitude, point.longitude, NEARBY_KM).filter { it.summary.id != canyonId }
            }.orEmpty()
            _uiState.update { state ->
                if (!fillPlace) return@update state.copy(nearby = nearby)
                val (draft, filled) = state.draft.withPlace(place, state.placeFromPoint)
                state.copy(
                    draft = draft,
                    placeFromPoint = filled,
                    nearby = nearby,
                    problems = if (state.showProblems) draft.problems() else state.problems,
                )
            }
        }
    }

    private companion object {
        const val NEARBY_KM = 2.0
    }
}

/**
 * Fills the place fields from [place] (the canyon nearest to the point), with the spelling of the
 * site (the zone filters then find the canyon): the blank ones, and those still as [filledBefore]
 * left them, so they follow a point moved elsewhere. A value typed by hand stays. The commune stays
 * to type: the nearest canyon is often in the next one. Returns the draft and the values now filled
 * from the point.
 */
internal fun SheetDraft.withPlace(
    place: CanyonPlace?,
    filledBefore: Map<SheetField, String> = emptyMap(),
): Pair<SheetDraft, Map<SheetField, String>> {
    var draft = this
    val filled = mutableMapOf<SheetField, String>()
    listOf(
        SheetField.PAYS to place?.pays,
        SheetField.REGION to place?.region,
        SheetField.DEPARTEMENT to place?.departement,
        SheetField.MASSIF to place?.massif,
        SheetField.BASSIN to place?.bassin,
    ).forEach { (field, value) ->
        val current = draft[field]
        val followsPoint = current.isBlank() || current == filledBefore[field]
        if (!followsPoint) return@forEach
        val next = value?.takeIf { it.isNotBlank() }.orEmpty()
        draft = draft.with(field, next)
        if (next.isNotEmpty()) filled[field] = next
    }
    return draft to filled
}
