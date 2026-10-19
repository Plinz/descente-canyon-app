package fr.descentecanyon.app.ui.canyon

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonEdfPracticability
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.NearbyCanyon
import fr.descentecanyon.app.domain.repository.CanyonNotesRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.domain.repository.NotificationCenterRepository
import fr.descentecanyon.app.domain.repository.RecentCanyonsRepository
import fr.descentecanyon.app.domain.usecase.GetCanyonDetailUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonPreviewUseCase
import fr.descentecanyon.app.domain.usecase.GetNearbyCanyonsUseCase
import fr.descentecanyon.app.domain.usecase.ToggleFavoriteUseCase
import fr.descentecanyon.app.perf.PerformanceTrace
import fr.descentecanyon.app.ui.common.UiText
import fr.descentecanyon.app.ui.common.toUiText
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CanyonDetailUiState(
    val canyonDetail: CanyonDetail? = null,
    val watershedGeometryJson: String? = null,
    val isLoading: Boolean = false,
    val isRefreshingDetail: Boolean = false,
    val isLoadingPhotos: Boolean = false,
    val photoError: UiText? = null,
    val isLoadingDebits: Boolean = false,
    val debitError: UiText? = null,
    /** Report authors who are site members: only they have a profile to open. */
    val memberAuthors: Set<String> = emptySet(),
    val error: UiText? = null,
    val weather: CanyonWeather? = null,
    val isLoadingWeather: Boolean = false,
    val weatherError: UiText? = null,
    val edfStatus: CanyonEdfPracticability? = null,
    val isLoadingEdfStatus: Boolean = false,
    val edfStatusError: UiText? = null,
    val edfStatusSourceUrl: String? = null,
    val predictions: CanyonDebitPredictions? = null,
    val isLoadingPredictions: Boolean = false,
    val predictionError: UiText? = null,
    val isFavorite: Boolean = false,
    val isDebitNotificationsEnabled: Boolean = false,
    val downloadingPhotoIds: Set<Long> = emptySet(),
    val isOnline: Boolean = true,
    val transientMessage: UiText? = null,
    /** The user's private note about this canyon. */
    val personalNote: String? = null,
    /** Canyons around this one, nearest first. */
    val nearbyCanyons: List<NearbyCanyon> = emptyList(),
    /** The user's outings in this canyon, most recent first. */
    val logbook: List<LogbookEntry> = emptyList(),
    /** Companions of earlier outings, offered when logging one. */
    val knownCompanions: List<String> = emptyList(),
)

/** State owned by the view model itself: the canyon detail and the user's choices about it. */
private data class CanyonDetailState(
    val canyonDetail: CanyonDetail? = null,
    val watershedGeometryJson: String? = null,
    val isLoading: Boolean = false,
    val isRefreshingDetail: Boolean = false,
    val error: UiText? = null,
    val isFavorite: Boolean = false,
    val isDebitNotificationsEnabled: Boolean = false,
    val isOnline: Boolean = true,
    val transientMessage: UiText? = null,
    val personalNote: String? = null,
    val nearbyCanyons: List<NearbyCanyon> = emptyList(),
    val logbook: List<LogbookEntry> = emptyList(),
    val knownCompanions: List<String> = emptyList(),
)

/**
 * Canyon detail screen: loads the detail (catalog preview first, then the full detail, refreshed
 * from the site when stale) and delegates the other sections to [CanyonMediaController] (photos,
 * debits) and [CanyonConditionsController] (weather, EDF status, predictions).
 */
@HiltViewModel
class CanyonDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getCanyonPreviewUseCase: GetCanyonPreviewUseCase,
    private val getCanyonDetailUseCase: GetCanyonDetailUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val canyonRepository: CanyonRepository,
    private val favoritesRepository: FavoritesRepository,
    private val notificationCenterRepository: NotificationCenterRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val conditions: CanyonConditionsController,
    private val media: CanyonMediaController,
    private val recentCanyonsRepository: RecentCanyonsRepository,
    private val canyonNotesRepository: CanyonNotesRepository,
    private val getNearbyCanyonsUseCase: GetNearbyCanyonsUseCase,
    private val logbookRepository: LogbookRepository,
) : ViewModel() {

    private val canyonId: Int = savedStateHandle["canyonId"]
        ?: throw IllegalArgumentException("canyonId is required")

    private val detailState = MutableStateFlow(CanyonDetailState())
    private var hasLoggedPrimaryContentReady = false
    private var hasRecordedView = false

    val uiState: StateFlow<CanyonDetailUiState> =
        combine(detailState, media.state, conditions.state, ::toUiState)
            .stateIn(viewModelScope, SharingStarted.Eagerly, CanyonDetailUiState())

    init {
        conditions.attach(canyonId, viewModelScope)
        media.attach(canyonId, viewModelScope, showMessage = { message -> detailState.update { it.copy(transientMessage = message) } })
        observeWatershed()
        observeFavorite()
        observeDebitNotificationPreference()
        observeConnectivity()
        observePersonalNote()
        observeNearbyCanyons()
        observeLogbook()
        loadCanyon(canyonId)
    }

    /** Saves the user's private note; an empty text deletes it. */
    fun savePersonalNote(text: String) {
        viewModelScope.launch { canyonNotesRepository.saveNote(canyonId, text) }
    }

    fun ensureWatershedGeometryLoaded() {
        if (detailState.value.watershedGeometryJson != null) return
        viewModelScope.launch {
            val geometryJson = canyonRepository.getWatershedGeometry(canyonId)
            detailState.update { state ->
                if (state.watershedGeometryJson != null) state else state.copy(watershedGeometryJson = geometryJson)
            }
        }
    }

    fun loadCanyon(id: Int) {
        conditions.onReloadStarted()
        hasLoggedPrimaryContentReady = false
        val hasExistingContent = detailState.value.canyonDetail != null
        PerformanceTrace.start(detailLoadKey(id), "canyon_detail_load", "canyonId" to id)
        detailState.update { it.copy(isLoading = !hasExistingContent, isRefreshingDetail = hasExistingContent, error = null) }
        media.refresh()

        viewModelScope.launch {
            getCanyonPreviewUseCase(id).onSuccess { preview ->
                val wasEmpty = detailState.value.canyonDetail == null
                detailState.update { state ->
                    if (state.canyonDetail != null) {
                        state
                    } else {
                        state.copy(canyonDetail = preview, isLoading = false, isRefreshingDetail = true, error = null)
                    }
                }
                conditions.loadFor(preview)
                recordView(id)
                if (wasEmpty) markPrimaryContentReady(id, "preview_ready")
            }
        }

        viewModelScope.launch {
            val startedAt = monotonicNowMs()
            getCanyonDetailUseCase(id).fold(
                onSuccess = { detail ->
                    detailState.update {
                        it.copy(
                            canyonDetail = mergeBaseCanyonDetail(detail, it.canyonDetail),
                            isLoading = false,
                            isRefreshingDetail = false,
                            error = null,
                        )
                    }
                    PerformanceTrace.logEvent(
                        event = "canyon_detail_full_ready",
                        "canyonId" to id,
                        "durationMs" to (monotonicNowMs() - startedAt),
                    )
                    markPrimaryContentReady(id, "detail_ready")
                    recordView(id)
                    conditions.loadFor(detail)
                },
                onFailure = { throwable ->
                    if (!hasLoggedPrimaryContentReady) {
                        PerformanceTrace.end(detailLoadKey(id), "failed", "canyonId" to id, "error" to throwable::class.simpleName)
                    }
                    val message = throwable.toUiText(fallback = R.string.canyon_detail_load_error)
                    val currentDetail = detailState.value.canyonDetail
                    if (currentDetail != null) {
                        // The catalog content stays on screen; only a short message tells the refresh failed.
                        detailState.update { it.copy(isLoading = false, isRefreshingDetail = false, transientMessage = message) }
                        conditions.loadFor(currentDetail)
                    } else {
                        detailState.update { it.copy(isLoading = false, isRefreshingDetail = false, error = message) }
                        media.onDetailUnavailable()
                        conditions.onDetailUnavailable()
                    }
                },
            )
        }
    }

    fun refreshDebitsAfterSubmission() = media.refreshDebitsAfterSubmission()

    fun downloadPhoto(photoId: Long) = media.downloadPhoto(photoId)

    fun onPersistedPhotoMissing(photoId: Long) = media.onPersistedPhotoMissing(photoId)

    fun reconcilePersistedPhotos() = media.reconcilePersistedPhotos()

    fun toggleFavorite() {
        viewModelScope.launch { toggleFavoriteUseCase(canyonId) }
    }

    fun toggleDebitNotifications() {
        viewModelScope.launch {
            val current = uiState.value.canyonDetail
            notificationCenterRepository.toggleCanyonFollow(
                canyonId = canyonId,
                canyonName = current?.canyon?.nom ?: "Canyon $canyonId",
                baselineDebits = current?.debits.orEmpty(),
            )
        }
    }

    fun clearTransientMessage() {
        detailState.update { it.copy(transientMessage = null) }
    }

    private fun observeWatershed() {
        viewModelScope.launch {
            canyonRepository.observeWatershed(canyonId).collect { watershed ->
                detailState.update { state -> state.copy(canyonDetail = state.canyonDetail?.copy(watershed = watershed)) }
            }
        }
    }

    private fun observeFavorite() {
        viewModelScope.launch {
            favoritesRepository.isFavorite(canyonId).collect { isFavorite -> detailState.update { it.copy(isFavorite = isFavorite) } }
        }
    }

    private fun observeDebitNotificationPreference() {
        viewModelScope.launch {
            notificationCenterRepository.observeIsCanyonFollowed(canyonId).collect { isFollowed ->
                detailState.update { it.copy(isDebitNotificationsEnabled = isFollowed) }
            }
        }
    }

    private fun recordView(id: Int) {
        if (hasRecordedView) return
        hasRecordedView = true
        viewModelScope.launch { suspendRunCatching { recentCanyonsRepository.recordView(id) } }
    }

    private fun observePersonalNote() {
        viewModelScope.launch {
            canyonNotesRepository.observeNote(canyonId).collect { note -> detailState.update { it.copy(personalNote = note) } }
        }
    }

    private fun observeConnectivity() {
        viewModelScope.launch {
            connectivityObserver.observe().collect { online ->
                val wasOnline = detailState.value.isOnline
                detailState.update { it.copy(isOnline = online) }
                // Back in coverage: the weather and estimates that failed (or came from the cache) are reloaded.
                if (online && !wasOnline) detailState.value.canyonDetail?.let(conditions::loadFor)
            }
        }
    }

    /** Logs or edits an outing in this canyon. */
    fun saveLogbookEntry(entry: LogbookEntry) {
        viewModelScope.launch { logbookRepository.save(entry.copy(canyonId = canyonId)) }
    }

    fun deleteLogbookEntry(id: Long) {
        viewModelScope.launch { logbookRepository.delete(id) }
    }

    private fun observeLogbook() {
        viewModelScope.launch {
            logbookRepository.observeForCanyon(canyonId).collect { entries ->
                detailState.update { it.copy(logbook = entries) }
                // Refreshed with the logbook: a name typed in the last outing is offered in the next one.
                val companions = suspendRunCatching { logbookRepository.knownCompanions() }.getOrDefault(emptyList())
                detailState.update { it.copy(knownCompanions = companions) }
            }
        }
    }

    private fun observeNearbyCanyons() {
        viewModelScope.launch {
            getNearbyCanyonsUseCase(canyonId).collect { nearby -> detailState.update { it.copy(nearbyCanyons = nearby) } }
        }
    }

    private fun markPrimaryContentReady(id: Int, outcome: String) {
        if (hasLoggedPrimaryContentReady) return
        hasLoggedPrimaryContentReady = true
        PerformanceTrace.end(detailLoadKey(id), outcome, "canyonId" to id)
    }
}

private fun toUiState(detail: CanyonDetailState, media: CanyonMediaState, conditions: CanyonConditionsState): CanyonDetailUiState =
    CanyonDetailUiState(
        canyonDetail = detail.canyonDetail?.let { canyon ->
            canyon.copy(photos = media.photos ?: canyon.photos, debits = media.debits ?: canyon.debits)
        },
        watershedGeometryJson = detail.watershedGeometryJson,
        isLoading = detail.isLoading,
        isRefreshingDetail = detail.isRefreshingDetail,
        isLoadingPhotos = media.isLoadingPhotos,
        photoError = media.photoError,
        isLoadingDebits = media.isLoadingDebits,
        debitError = media.debitError,
        memberAuthors = media.memberAuthors,
        error = detail.error,
        weather = conditions.weather,
        isLoadingWeather = conditions.isLoadingWeather,
        weatherError = conditions.weatherError,
        edfStatus = conditions.edfStatus,
        isLoadingEdfStatus = conditions.isLoadingEdfStatus,
        edfStatusError = conditions.edfStatusError,
        edfStatusSourceUrl = conditions.edfStatusSourceUrl,
        predictions = conditions.predictions,
        isLoadingPredictions = conditions.isLoadingPredictions,
        predictionError = conditions.predictionError,
        isFavorite = detail.isFavorite,
        isDebitNotificationsEnabled = detail.isDebitNotificationsEnabled,
        downloadingPhotoIds = media.downloadingPhotoIds,
        isOnline = detail.isOnline,
        nearbyCanyons = detail.nearbyCanyons,
        logbook = detail.logbook,
        knownCompanions = detail.knownCompanions,
        transientMessage = detail.transientMessage,
        personalNote = detail.personalNote,
    )

internal fun mergeBaseCanyonDetail(newDetail: CanyonDetail, currentDetail: CanyonDetail?): CanyonDetail = if (currentDetail == null) {
    newDetail
} else {
    newDetail.copy(
        photos = currentDetail.photos,
        debits = currentDetail.debits,
        tracks = newDetail.tracks.ifEmpty { currentDetail.tracks },
        watershed = newDetail.watershed ?: currentDetail.watershed,
    )
}

private fun detailLoadKey(canyonId: Int): String = "screen.canyon.$canyonId.detail"

private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000
