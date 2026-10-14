package fr.descentecanyon.app.ui.canyon

import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.CanyonPhoto
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.PhotoRepository
import fr.descentecanyon.app.domain.usecase.DownloadPhotoForOfflineUseCase
import fr.descentecanyon.app.perf.PerformanceTrace
import fr.descentecanyon.app.ui.common.UiText
import fr.descentecanyon.app.ui.common.toUiText
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CanyonMediaState(
    /** Photos cached for the canyon, null until the cache has been read. */
    val photos: List<CanyonPhoto>? = null,
    /** Debit observations cached for the canyon, null until the cache has been read. */
    val debits: List<Debit>? = null,
    val isLoadingPhotos: Boolean = false,
    val photoError: UiText? = null,
    val isLoadingDebits: Boolean = false,
    val debitError: UiText? = null,
    val downloadingPhotoIds: Set<Long> = emptySet(),
)

/**
 * Photos and debit observations of the canyon detail screen: shown from the local cache and
 * refreshed from the site when online.
 */
class CanyonMediaController @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val debitRepository: DebitRepository,
    private val downloadPhotoForOfflineUseCase: DownloadPhotoForOfflineUseCase,
    private val connectivityObserver: ConnectivityObserver,
) {

    private val _state = MutableStateFlow(CanyonMediaState())
    val state: StateFlow<CanyonMediaState> = _state.asStateFlow()

    private lateinit var scope: CoroutineScope
    private var canyonId: Int = 0
    private var showMessage: (UiText) -> Unit = {}

    fun attach(canyonId: Int, scope: CoroutineScope, showMessage: (UiText) -> Unit) {
        this.canyonId = canyonId
        this.scope = scope
        this.showMessage = showMessage
        scope.launch {
            photoRepository.observePhotos(canyonId).collect { photos -> _state.update { it.copy(photos = photos) } }
        }
        scope.launch {
            debitRepository.getDebitsForCanyon(canyonId).collect { result ->
                result.onSuccess { debits -> _state.update { it.copy(debits = debits) } }
            }
        }
    }

    /** Refreshes photos and debits from the site. */
    fun refresh() {
        _state.update { it.copy(isLoadingPhotos = true, photoError = null, isLoadingDebits = true, debitError = null) }
        scope.launch { refreshPhotos() }
        scope.launch { refreshDebits() }
    }

    fun refreshDebitsAfterSubmission() {
        _state.update { it.copy(isLoadingDebits = true, debitError = null) }
        scope.launch { refreshDebits() }
    }

    /** The canyon could not be loaded at all: no section is loading anymore. */
    fun onDetailUnavailable() {
        _state.update { it.copy(isLoadingPhotos = false, photoError = null, isLoadingDebits = false, debitError = null) }
    }

    fun downloadPhoto(photoId: Long) {
        if (photoId == 0L || _state.value.downloadingPhotoIds.contains(photoId)) return

        scope.launch {
            _state.update { it.copy(downloadingPhotoIds = it.downloadingPhotoIds + photoId) }
            downloadPhotoForOfflineUseCase(photoId).fold(
                onSuccess = { localPath ->
                    _state.update { state ->
                        state.copy(
                            photos = state.photos?.map { photo -> if (photo.id == photoId) photo.copy(localPath = localPath) else photo },
                            downloadingPhotoIds = state.downloadingPhotoIds - photoId,
                        )
                    }
                    showMessage(UiText.res(R.string.photo_downloaded))
                },
                onFailure = { throwable ->
                    _state.update { it.copy(downloadingPhotoIds = it.downloadingPhotoIds - photoId) }
                    showMessage(throwable.toUiText(fallback = R.string.photo_load_error_now, connectivity = R.string.photo_load_error_now))
                },
            )
        }
    }

    fun onPersistedPhotoMissing(photoId: Long) {
        if (photoId == 0L) return
        scope.launch { photoRepository.clearLocalPath(photoId) }
    }

    fun reconcilePersistedPhotos() {
        scope.launch { suspendRunCatching { photoRepository.reconcileDeletedLocalPhotos(canyonId) } }
    }

    private suspend fun refreshPhotos() {
        if (!connectivityObserver.isCurrentlyOnline()) {
            PerformanceTrace.logEvent("canyon_photos_refresh_skipped", "canyonId" to canyonId, "reason" to "offline")
            _state.update { it.copy(isLoadingPhotos = false, photoError = UiText.res(R.string.photos_load_error_network)) }
            return
        }
        PerformanceTrace.start(photoRefreshKey(canyonId), "canyon_photos_refresh", "canyonId" to canyonId)
        photoRepository.refreshPhotos(canyonId).fold(
            onSuccess = { photos ->
                PerformanceTrace.end(photoRefreshKey(canyonId), "ready", "canyonId" to canyonId, "photoCount" to photos.size)
                _state.update { it.copy(isLoadingPhotos = false, photoError = null) }
            },
            onFailure = { throwable ->
                PerformanceTrace.end(photoRefreshKey(canyonId), "failed", "canyonId" to canyonId, "error" to throwable::class.simpleName)
                _state.update {
                    it.copy(
                        isLoadingPhotos = false,
                        photoError = throwable.toUiText(
                            fallback = R.string.photos_unavailable_now,
                            connectivity = R.string.photos_load_error_network,
                        ),
                    )
                }
            },
        )
    }

    private suspend fun refreshDebits() {
        if (!connectivityObserver.isCurrentlyOnline()) {
            PerformanceTrace.logEvent("canyon_debits_refresh_skipped", "canyonId" to canyonId, "reason" to "offline")
            val message = UiText.res(R.string.debits_load_error_network)
            _state.update { it.copy(isLoadingDebits = false, debitError = message) }
            showMessage(message)
            return
        }
        PerformanceTrace.start(debitRefreshKey(canyonId), "canyon_debits_refresh", "canyonId" to canyonId)
        debitRepository.refreshDebits(canyonId).fold(
            onSuccess = { debits ->
                PerformanceTrace.end(debitRefreshKey(canyonId), "ready", "canyonId" to canyonId, "debitCount" to debits.size)
                _state.update { it.copy(isLoadingDebits = false, debitError = null) }
            },
            onFailure = { throwable ->
                PerformanceTrace.end(debitRefreshKey(canyonId), "failed", "canyonId" to canyonId, "error" to throwable::class.simpleName)
                val message = throwable.toUiText(
                    fallback = R.string.debits_unavailable_now,
                    connectivity = R.string.debits_load_error_network,
                )
                _state.update { it.copy(isLoadingDebits = false, debitError = message) }
                showMessage(message)
            },
        )
    }

    private companion object {
        fun debitRefreshKey(canyonId: Int): String = "screen.canyon.$canyonId.debits"

        fun photoRefreshKey(canyonId: Int): String = "screen.canyon.$canyonId.photos"
    }
}
