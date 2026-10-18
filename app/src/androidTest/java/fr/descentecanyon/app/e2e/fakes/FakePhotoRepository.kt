package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.model.CanyonPhoto
import fr.descentecanyon.app.domain.repository.PhotoRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class FakePhotoRepository @Inject constructor() : PhotoRepository {
    override fun observePhotos(canyonId: Int): Flow<List<CanyonPhoto>> =
        E2eFixtureState.canyonDetails.map { details -> details[canyonId]?.photos.orEmpty() }

    override suspend fun refreshPhotos(canyonId: Int, maxAgeMs: Long): Result<List<CanyonPhoto>> =
        Result.success(E2eFixtureState.canyonDetails.value[canyonId]?.photos.orEmpty())

    override suspend fun downloadPhoto(photoId: Long): Result<String> {
        val localPath = "/data/local/tmp/photo-$photoId.jpg"
        E2eFixtureState.updatePhotoLocalPath(photoId, localPath)
        return Result.success(localPath)
    }

    override suspend fun clearLocalPath(photoId: Long) {
        E2eFixtureState.updatePhotoLocalPath(photoId, null)
    }

    override suspend fun reconcileDeletedLocalPhotos(canyonId: Int) = Unit
}
