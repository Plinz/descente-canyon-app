package fr.descentecanyon.app.ui.outing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.toAppError
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.ShareCodec
import fr.descentecanyon.app.domain.model.SharedContent
import fr.descentecanyon.app.domain.model.SharedOuting
import fr.descentecanyon.app.domain.model.SharedStay
import fr.descentecanyon.app.domain.model.Stay
import fr.descentecanyon.app.domain.model.haversineKm
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.PlaceRepository
import fr.descentecanyon.app.domain.repository.ShareRepository
import fr.descentecanyon.app.domain.repository.StayRepository
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why a link cannot be shown. */
enum class SharedLinkFailure {
    /** A short link is read from the server: no network now. */
    OFFLINE,

    /** Expired (a while after its day), deleted, or a code mistyped. */
    NOT_FOUND,

    /** Written by a newer app, or not a link of the app. */
    UNREADABLE,

    /** No server built into this version, or it did not answer. */
    UNAVAILABLE,
}

/** A canyon of a link, with what this phone's catalog knows of it (null: not in it yet). */
data class SharedCanyon(val id: Int, val name: String, val item: CanyonSearchItem?)

sealed interface SharedLinkUiState {
    data object Loading : SharedLinkUiState

    data class Failed(val failure: SharedLinkFailure) : SharedLinkUiState

    data class Outing(
        val outing: SharedOuting,
        val code: String?,
        val version: Int,
        val canyons: List<SharedCanyon>,
        /** The same outing already on this phone (added from this link, or planned on that day). */
        val existing: PlannedOuting?,
        /** Its day is gone by: it can only be planned again, on another day. */
        val isPast: Boolean,
    ) : SharedLinkUiState {
        /** Every canyon is in the catalog: the outing can be planned on this phone. */
        val canPlan: Boolean get() = canyons.all { it.item != null }
    }

    data class Stay(
        val stay: SharedStay,
        val canyons: Map<Int, SharedCanyon>,
        /** The stay added already: its id. */
        val addedStayId: Long?,
        /** One of this phone's stays covers some of these days: one place a day, so it is not added. */
        val overlaps: Boolean,
        val isPast: Boolean,
    ) : SharedLinkUiState

    data class CanyonList(val title: String, val canyons: List<SharedCanyon>, val favoriteIds: Set<Int>, val added: Boolean = false) :
        SharedLinkUiState
}

/**
 * A link someone shared, opened in the app: a short link read from the server, or an outing the link
 * carries whole (QR code). Nothing is added without the person's tap.
 */
@HiltViewModel
class SharedLinkViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val shareRepository: ShareRepository,
    private val canyonRepository: CanyonRepository,
    private val outingRepository: OutingRepository,
    private val stayRepository: StayRepository,
    private val placeRepository: PlaceRepository,
    private val favoritesRepository: FavoritesRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val clock: Clock,
) : ViewModel() {
    private val code: String? = savedStateHandle.get<String>("code")?.takeIf { it.isNotBlank() }
    private val payload: String? = savedStateHandle.get<String>("payload")?.takeIf { it.isNotBlank() }

    private val state = MutableStateFlow<SharedLinkUiState>(SharedLinkUiState.Loading)
    val uiState: StateFlow<SharedLinkUiState> = state.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        state.value = SharedLinkUiState.Loading
        viewModelScope.launch {
            state.value = when {
                payload != null -> ShareCodec.unpack(payload)?.let { outingState(it, code = null, version = 0) }
                    ?: SharedLinkUiState.Failed(SharedLinkFailure.UNREADABLE)

                code != null -> fromServer(code)

                else -> SharedLinkUiState.Failed(SharedLinkFailure.UNREADABLE)
            }
        }
    }

    private suspend fun fromServer(code: String): SharedLinkUiState {
        if (!connectivityObserver.isCurrentlyOnline()) return SharedLinkUiState.Failed(SharedLinkFailure.OFFLINE)
        val received = shareRepository.fetch(code).getOrElse { error ->
            return SharedLinkUiState.Failed(
                when (error.toAppError()) {
                    AppError.NotFound -> SharedLinkFailure.NOT_FOUND
                    AppError.Offline, AppError.NetworkFailure, AppError.Timeout -> SharedLinkFailure.OFFLINE
                    AppError.UnexpectedResponse -> SharedLinkFailure.UNREADABLE
                    else -> SharedLinkFailure.UNAVAILABLE
                },
            )
        }
        return when (val content = received.content) {
            is SharedContent.Outing -> outingState(content.outing, received.code, received.version)
            is SharedContent.Stay -> stayState(content.stay)
            is SharedContent.CanyonList -> listState(content)
        }
    }

    private suspend fun catalog(): Map<Int, CanyonSearchItem> = canyonRepository.observeSearchCatalog().first().associateBy { it.id }

    private suspend fun outingState(outing: SharedOuting, code: String?, version: Int): SharedLinkUiState.Outing {
        val catalog = catalog()
        val names = outing.summary.names
        val canyons = outing.canyonIds.mapIndexed { index, id ->
            val item = catalog[id]
            SharedCanyon(id, item?.nom ?: names.getOrNull(index).orEmpty(), item)
        }
        val today = LocalDate.now(clock)
        val outings = outingRepository.observeAll().first()
        val existing = outings.firstOrNull { code != null && it.choices?.link?.code == code }
            ?: outing.date?.let { day -> outings.firstOrNull { it.canyonId == outing.canyonId && it.choices?.date == day } }
        return SharedLinkUiState.Outing(
            outing = outing,
            code = code,
            version = version,
            canyons = canyons,
            existing = existing,
            isPast = outing.date?.isBefore(today) == true,
        )
    }

    private suspend fun stayState(stay: SharedStay): SharedLinkUiState.Stay {
        val catalog = catalog()
        val canyons = stay.outings.flatMap { outing ->
            outing.canyonIds.mapIndexed { index, id ->
                SharedCanyon(id, catalog[id]?.nom ?: outing.summary.names.getOrNull(index).orEmpty(), catalog[id])
            }
        }.associateBy { it.id }
        val stays = stayRepository.observeAll().first()
        val added = addedStay(stay, stays)
        return SharedLinkUiState.Stay(
            stay = stay,
            canyons = canyons,
            addedStayId = added?.id,
            overlaps = added == null && stays.any { it.overlaps(Stay(0, stay.start, stay.end)) },
            isPast = stay.end.isBefore(LocalDate.now(clock)),
        )
    }

    private suspend fun addedStay(stay: SharedStay, stays: List<Stay>): Stay? {
        val places = placeRepository.observeAll().first()
        return stays.firstOrNull { candidate ->
            candidate.start == stay.start && candidate.end == stay.end &&
                places.firstOrNull { it.id == candidate.placeId }?.let { samePlace(it, stay) } == true
        }
    }

    private fun samePlace(place: Place, stay: SharedStay): Boolean =
        haversineKm(place.latitude, place.longitude, stay.place.latitude, stay.place.longitude) <= SAME_PLACE_KM

    private suspend fun listState(list: SharedContent.CanyonList): SharedLinkUiState.CanyonList {
        val catalog = catalog()
        val canyons = list.canyonIds.map { id -> SharedCanyon(id, catalog[id]?.nom.orEmpty(), catalog[id]) }
        return SharedLinkUiState.CanyonList(list.title, canyons, favoritesRepository.observeFavoriteIds().first())
    }

    /** The stay's place (one of this phone's when it is there) and its days; its outings are added day by day. */
    fun addStay() {
        val current = state.value as? SharedLinkUiState.Stay ?: return
        if (current.addedStayId != null || current.overlaps) return
        viewModelScope.launch {
            val place = current.stay.place
            val placeId = placeRepository.observeAll().first().firstOrNull { samePlace(it, current.stay) }?.id
                ?: placeRepository.save(Place(name = place.name, latitude = place.latitude, longitude = place.longitude))
            val stayId = stayRepository.save(Stay(placeId, current.stay.start, current.stay.end))
            state.value = current.copy(addedStayId = stayId)
        }
    }

    /** Every canyon of the list in this phone's catalog goes to the favorites. */
    fun addListToFavorites() {
        val current = state.value as? SharedLinkUiState.CanyonList ?: return
        viewModelScope.launch {
            current.canyons.filter { it.item != null && it.id !in current.favoriteIds }.forEach { favoritesRepository.addFavorite(it.id) }
            state.update {
                (it as? SharedLinkUiState.CanyonList)?.copy(
                    favoriteIds = it.favoriteIds + current.canyons.map(SharedCanyon::id),
                    added = true,
                )
                    ?: it
            }
        }
    }

    private companion object {
        const val SAME_PLACE_KM = 0.15
    }
}
