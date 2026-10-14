package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.repository.PhotoRepository
import javax.inject.Inject

class DownloadPhotoForOfflineUseCase @Inject constructor(private val photoRepository: PhotoRepository) {
    suspend operator fun invoke(photoId: Long): Result<String> = photoRepository.downloadPhoto(photoId)
}
