package fr.descentecanyon.app.ui.canyon

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.CanyonPhoto
import fr.descentecanyon.app.domain.repository.PhotoRepository
import fr.descentecanyon.app.domain.usecase.DownloadPhotoForOfflineUseCase
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PhotoGalleryUiState(
    val photos: List<CanyonPhoto> = emptyList(),
    val currentIndex: Int = 0,
    val isLoading: Boolean = true,
    val downloadingPhotoIds: Set<Long> = emptySet(),
)

@HiltViewModel
class PhotoGalleryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val photoRepository: PhotoRepository,
    private val downloadPhotoForOfflineUseCase: DownloadPhotoForOfflineUseCase,
) : ViewModel() {

    private val canyonId: Int = checkNotNull(savedStateHandle["canyonId"])
    private val initialPhotoId: Long = checkNotNull(savedStateHandle["initialPhotoId"])
    private val selectedPhotoId = MutableStateFlow(initialPhotoId.takeIf { it != 0L })
    private val downloadingPhotoIds = MutableStateFlow(emptySet<Long>())

    val uiState: StateFlow<PhotoGalleryUiState> = photoRepository.observePhotos(canyonId)
        .combine(selectedPhotoId) { photos, selectedId ->
            photos to selectedId
        }
        .combine(downloadingPhotoIds) { (photos, selectedId), downloadingIds ->
            PhotoGalleryUiState(
                photos = photos,
                currentIndex = photos.indexForSelectedId(selectedId),
                isLoading = false,
                downloadingPhotoIds = downloadingIds,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PhotoGalleryUiState(),
        )

    fun downloadPhoto(photoId: Long) {
        if (photoId == 0L || downloadingPhotoIds.value.contains(photoId)) return

        viewModelScope.launch {
            downloadingPhotoIds.update { it + photoId }
            downloadPhotoForOfflineUseCase(photoId)
            downloadingPhotoIds.update { it - photoId }
        }
    }

    fun onPageChanged(page: Int) {
        val photos = uiState.value.photos
        selectedPhotoId.value = photos.getOrNull(page)?.id ?: selectedPhotoId.value
    }

    fun onPersistedPhotoMissing(photoId: Long) {
        if (photoId == 0L) return

        viewModelScope.launch {
            photoRepository.clearLocalPath(photoId)
        }
    }

    fun reconcilePersistedPhotos() {
        viewModelScope.launch {
            suspendRunCatching { photoRepository.reconcileDeletedLocalPhotos(canyonId) }
        }
    }
}

private fun List<CanyonPhoto>.indexForSelectedId(selectedId: Long?): Int {
    if (isEmpty()) return 0
    if (selectedId == null) return 0
    return indexOfFirst { it.id == selectedId }
        .takeIf { it >= 0 }
        ?: 0
}
