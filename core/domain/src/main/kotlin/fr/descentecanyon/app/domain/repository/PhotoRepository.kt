package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CanyonPhoto
import kotlinx.coroutines.flow.Flow

interface PhotoRepository {
    fun observePhotos(canyonId: Int): Flow<List<CanyonPhoto>>
    suspend fun refreshPhotos(canyonId: Int): Result<List<CanyonPhoto>>
    suspend fun downloadPhoto(photoId: Long): Result<String>
    suspend fun clearLocalPath(photoId: Long)
    suspend fun reconcileDeletedLocalPhotos(canyonId: Int)
}
