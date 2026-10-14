package fr.descentecanyon.app.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PublicPhotoStorage @Inject constructor(@param:ApplicationContext private val context: Context) {

    fun saveImageFile(sourceFile: File, displayName: String, mimeType: String): Uri {
        val resolver = context.contentResolver
        val preQTargetFile = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                PUBLIC_ALBUM_NAME,
            )
            directory.mkdirs()
            uniqueFile(directory, displayName)
        } else {
            null
        }

        val mediaDisplayName = preQTargetFile?.name ?: displayName
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, mediaDisplayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$PUBLIC_ALBUM_NAME")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                put(MediaStore.Images.Media.DATA, preQTargetFile?.absolutePath)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: appError(AppError.StorageFailure)

        var success = false
        try {
            resolver.openOutputStream(uri)?.use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: appError(AppError.StorageFailure)
            success = true
            return uri
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (success) {
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                        null,
                        null,
                    )
                } else {
                    resolver.delete(uri, null, null)
                }
            } else if (!success) {
                resolver.delete(uri, null, null)
            }
        }
    }

    private fun uniqueFile(directory: File, displayName: String): File {
        val baseName = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', "")
        var candidate = File(directory, displayName)
        var index = 1
        while (candidate.exists()) {
            val suffix = if (extension.isBlank()) "-$index" else "-$index.$extension"
            candidate = File(directory, "$baseName$suffix")
            index += 1
        }
        return candidate
    }

    private companion object {
        const val PUBLIC_ALBUM_NAME = "Descente-Canyon"
    }
}

internal fun publicPhotoDisplayName(canyonId: Int, photoId: Long, extension: String): String =
    "descente-canyon-$canyonId-photo-$photoId.$extension"

internal fun imageExtensionFromUrl(url: String): String {
    val extension = url
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('/', "")
        .substringAfterLast('.', "")
        .lowercase()
        .takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }

    return extension
        ?.takeIf { imageMimeType(it).startsWith("image/") }
        ?: "jpg"
}

internal fun imageExtensionFromFile(file: File, fallbackUrl: String): String {
    val extension = file.extension
        .lowercase()
        .takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }

    return extension
        ?.takeIf { imageMimeType(it).startsWith("image/") }
        ?: imageExtensionFromUrl(fallbackUrl)
}

internal fun imageMimeType(extension: String): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    ?: if (extension == "jpg" || extension == "jpeg") "image/jpeg" else "image/jpeg"
