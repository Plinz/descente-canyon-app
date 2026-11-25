package fr.descentecanyon.app.ui.canyon

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.CommunityTrack
import fr.descentecanyon.app.domain.model.CommunityTracks
import fr.descentecanyon.app.domain.model.TrackRole
import fr.descentecanyon.app.domain.model.TrackVote
import fr.descentecanyon.app.domain.repository.AppServerRepository
import fr.descentecanyon.app.domain.repository.CommunityTrackRepository
import fr.descentecanyon.app.ui.common.UiText
import fr.descentecanyon.app.ui.common.toUiText
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CommunityTracksUiState(
    /** The server is configured, switched on and offers shared tracks. */
    val isAvailable: Boolean = false,
    val tracks: CommunityTracks? = null,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isOnline: Boolean = true,
    val isUploading: Boolean = false,
    val busyTrackIds: Set<String> = emptySet(),
    val message: UiText? = null,
)

private data class TransientState(
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val isUploading: Boolean = false,
    val busyTrackIds: Set<String> = emptySet(),
    val message: UiText? = null,
)

/** Shared GPS tracks of the canyon of the current screen (page or points map). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CommunityTracksViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: CommunityTrackRepository,
    appServerRepository: AppServerRepository,
    connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    private val canyonId: Int = savedStateHandle["canyonId"] ?: throw IllegalArgumentException("canyonId is required")
    private val transient = MutableStateFlow(TransientState())
    private val available = appServerRepository.observeState().map { it.communityTracksAvailable }.distinctUntilChanged()
    private var refreshedOnce = false

    val uiState: StateFlow<CommunityTracksUiState> = combine(
        available,
        available.flatMapLatest { if (it) repository.observeTracks(canyonId) else flowOf(null) },
        connectivityObserver.observe(),
        transient,
    ) { isAvailable, tracks, online, state ->
        CommunityTracksUiState(
            isAvailable = isAvailable,
            tracks = tracks,
            isLoading = state.isLoading,
            loadFailed = state.loadFailed,
            isOnline = online,
            isUploading = state.isUploading,
            busyTrackIds = state.busyTrackIds,
            message = state.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CommunityTracksUiState())

    init {
        viewModelScope.launch {
            combine(available, connectivityObserver.observe()) { isAvailable, online -> isAvailable && online }
                .distinctUntilChanged()
                .collect { canRefresh -> if (canRefresh && !refreshedOnce) refresh() }
        }
    }

    fun refresh() {
        if (transient.value.isLoading) return
        refreshedOnce = true
        transient.update { it.copy(isLoading = true, loadFailed = false) }
        viewModelScope.launch {
            val result = repository.refresh(canyonId)
            transient.update { it.copy(isLoading = false, loadFailed = result.isFailure) }
        }
    }

    fun upload(role: TrackRole, gpx: ByteArray) {
        if (transient.value.isUploading) return
        transient.update { it.copy(isUploading = true, message = null) }
        viewModelScope.launch {
            val result = repository.upload(canyonId, role, gpx)
            transient.update {
                it.copy(
                    isUploading = false,
                    message = result.fold(
                        onSuccess = { UiText.res(R.string.track_uploaded) },
                        onFailure = { error -> error.toUiText(connectivity = R.string.tracks_need_network) },
                    ),
                )
            }
        }
    }

    /** Votes [vote], or withdraws the vote when the track already has it. */
    fun vote(track: CommunityTrack, vote: TrackVote) {
        if (track.id in transient.value.busyTrackIds) return
        val wanted = if (track.myVote == vote) TrackVote.NONE else vote
        busy(track) {
            repository.vote(track, wanted).exceptionOrNull()?.let { error ->
                transient.update { it.copy(message = error.toUiText(connectivity = R.string.tracks_need_network)) }
            }
        }
    }

    fun delete(track: CommunityTrack) {
        if (track.id in transient.value.busyTrackIds) return
        busy(track) {
            val result = repository.delete(track)
            transient.update {
                it.copy(
                    message = result.fold(
                        onSuccess = { UiText.res(R.string.track_deleted) },
                        onFailure = { error -> error.toUiText(connectivity = R.string.tracks_need_network) },
                    ),
                )
            }
        }
    }

    fun showMessage(message: UiText) {
        transient.update { it.copy(message = message) }
    }

    fun consumeMessage() {
        transient.update { it.copy(message = null) }
    }

    private fun busy(track: CommunityTrack, block: suspend () -> Unit) {
        transient.update { it.copy(busyTrackIds = it.busyTrackIds + track.id, message = null) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                transient.update { it.copy(busyTrackIds = it.busyTrackIds - track.id) }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
