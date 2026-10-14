package fr.descentecanyon.app.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.local.user.dao.PhotoDao
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class LegacyPhotoStorageMigrator @Inject constructor(
    private val photoDao: PhotoDao,
    private val publicPhotoStorage: PublicPhotoStorage,
    @param:ApplicationContext private val context: Context,
) {

    suspend fun migrateOrDeleteLegacyPhotos() {
        withContext(Dispatchers.IO) {
            val legacyRoot = File(context.filesDir, LEGACY_PHOTO_DIRECTORY)
            val photos = photoDao.getPhotosWithLocalPath()

            photos.forEach { photo ->
                val localPath = photo.localPath ?: return@forEach
                if (!isLegacyPrivatePhotoPath(context, localPath)) return@forEach

                val legacyFile = File(localPath)
                val migratedPath = legacyFile
                    .takeIf { it.exists() && it.isFile && canWritePublicMedia() }
                    ?.let { file ->
                        runCatching {
                            val extension = imageExtensionFromFile(file, photo.url)
                            publicPhotoStorage.saveImageFile(
                                sourceFile = file,
                                displayName = publicPhotoDisplayName(photo.canyonId, photo.id, extension),
                                mimeType = imageMimeType(extension),
                            ).toString()
                        }.getOrNull()
                    }

                photoDao.updateLocalPath(photo.id, migratedPath)
                legacyFile.delete()
            }

            if (legacyRoot.exists()) {
                legacyRoot.deleteRecursively()
            }
        }
    }

    private fun canWritePublicMedia(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

internal const val LEGACY_PHOTO_DIRECTORY = "offline-photos"

internal fun isLegacyPrivatePhotoPath(context: Context, path: String): Boolean {
    if (path.startsWith("content://")) return false

    val legacyRoot = File(context.filesDir, LEGACY_PHOTO_DIRECTORY)
    val rootPath = runCatching { legacyRoot.canonicalPath }.getOrElse { legacyRoot.absolutePath }
    val filePath = runCatching { File(path).canonicalPath }.getOrElse { path }
    return filePath == rootPath || filePath.startsWith(rootPath + File.separator)
}
