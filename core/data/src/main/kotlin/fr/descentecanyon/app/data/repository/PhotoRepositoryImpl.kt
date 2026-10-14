package fr.descentecanyon.app.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.local.user.dao.PhotoDao
import fr.descentecanyon.app.data.local.user.entity.PhotoEntity
import fr.descentecanyon.app.data.mapper.toDomain
import fr.descentecanyon.app.data.mapper.toEntity
import fr.descentecanyon.app.data.network.SiteHttpClient
import fr.descentecanyon.app.data.remote.scraper.CanyonScraper
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.CanyonPhoto
import fr.descentecanyon.app.domain.repository.PhotoRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class PhotoRepositoryImpl @Inject constructor(
    private val photoDao: PhotoDao,
    private val scraper: CanyonScraper,
    private val siteClient: SiteHttpClient,
    private val publicPhotoStorage: PublicPhotoStorage,
    @param:ApplicationContext private val context: Context,
) : PhotoRepository {

    override fun observePhotos(canyonId: Int): Flow<List<CanyonPhoto>> = photoDao.observeByCanyonId(canyonId).map { photos ->
        photos.map { it.withoutLegacyLocalPath().toDomain() }
    }

    override suspend fun refreshPhotos(canyonId: Int): Result<List<CanyonPhoto>> = suspendRunCatching {
        withContext(Dispatchers.IO) {
            val existingByUrl = photoDao.getByCanyonId(canyonId).associateBy { it.url }
            val entities = scraper.scrapeCanyonPhotos(
                canyonId = canyonId,
                timeoutMs = REFRESH_TIMEOUT_MS,
            ).getOrThrow().map { scrapedPhoto ->
                val existing = existingByUrl[scrapedPhoto.url]
                scrapedPhoto.toEntity().copy(
                    id = existing?.id ?: 0,
                    localPath = existing?.localPath?.takeIf { isReusableLocalPath(it) },
                )
            }

            photoDao.replaceForCanyon(canyonId, entities)

            entities.map { it.toDomain() }
        }
    }

    override suspend fun downloadPhoto(photoId: Long): Result<String> = suspendRunCatching {
        withContext(Dispatchers.IO) {
            val photo = photoDao.getById(photoId)
                ?: appError(AppError.DataUnavailable)

            photo.localPath?.takeIf { isReusableLocalPath(it) }?.let { return@withContext it }

            val extension = imageExtensionFromUrl(photo.url)
            val tempDir = File(context.cacheDir, "photo-downloads")
            tempDir.mkdirs()
            val targetFile = File(tempDir, "photo-$photoId.$extension")

            try {
                siteClient.download(url = photo.url, target = targetFile)

                val publicUri = publicPhotoStorage.saveImageFile(
                    sourceFile = targetFile,
                    displayName = publicPhotoDisplayName(photo.canyonId, photoId, extension),
                    mimeType = imageMimeType(extension),
                )
                val publicPath = publicUri.toString()
                photoDao.updateLocalPath(photoId, publicPath)
                publicPath
            } finally {
                targetFile.delete()
            }
        }
    }

    override suspend fun clearLocalPath(photoId: Long) {
        withContext(Dispatchers.IO) {
            photoDao.updateLocalPath(photoId, null)
        }
    }

    override suspend fun reconcileDeletedLocalPhotos(canyonId: Int) {
        withContext(Dispatchers.IO) {
            photoDao.getByCanyonId(canyonId).forEach { photo ->
                val localPath = photo.localPath ?: return@forEach
                if (!isReusableLocalPath(localPath)) {
                    photoDao.updateLocalPath(photo.id, null)
                }
            }
        }
    }

    private fun PhotoEntity.withoutLegacyLocalPath(): PhotoEntity = copy(
        localPath = localPath?.takeUnless {
            isLegacyPrivatePhotoPath(context, it)
        },
    )

    private fun isReusableLocalPath(path: String): Boolean {
        if (isLegacyPrivatePhotoPath(context, path)) return false
        if (path.startsWith("content://")) {
            return isReusableMediaUri(path.toUri())
        }
        return File(path).exists()
    }

    private fun isReusableMediaUri(uri: Uri): Boolean {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.IS_PENDING)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.MediaColumns.IS_TRASHED)
            }
        }.toTypedArray()

        val isVisibleMedia = runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val pendingIndex = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                    if (pendingIndex >= 0 && cursor.getInt(pendingIndex) == 1) return@use false
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val trashedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                    if (trashedIndex >= 0 && cursor.getInt(trashedIndex) == 1) return@use false
                }
                true
            } ?: false
        }.getOrDefault(false)

        if (!isVisibleMedia) return false

        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    private companion object {
        const val REFRESH_TIMEOUT_MS = 15_000L
    }
}
