package fr.descentecanyon.app.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.AppBuildInfo
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies of the large JSON assets in `filesDir/debit-model-cache`, read by byte offset (an APK
 * asset cannot be opened with RandomAccessFile). Every store goes through this one cache, so the
 * cleanup of the copies left by older app versions never deletes a current copy another store
 * is still reading.
 */
@Singleton
class DebitAssetFileCache internal constructor(
    private val directory: File,
    private val versionCode: Int,
    private val openAsset: (String) -> InputStream,
) {

    @Inject
    constructor(@ApplicationContext context: Context, appBuildInfo: AppBuildInfo) : this(
        directory = File(context.filesDir, DIRECTORY_NAME),
        versionCode = appBuildInfo.versionCode,
        openAsset = { path -> context.assets.open(path) },
    )

    private var cleaned = false

    /** The copy of [assetPath] for this app version, named [name]`-v<versionCode>.json`, written on first use. */
    @Synchronized
    fun file(assetPath: String, name: String): File {
        directory.mkdirs()
        val suffix = "-v$versionCode.json"
        if (!cleaned) {
            // Copies of older versions (with their ONNX models, 60 MB) and interrupted copies are dead weight.
            directory.listFiles()?.filterNot { it.name.endsWith(suffix) }?.forEach { it.deleteRecursively() }
            cleaned = true
        }
        val target = File(directory, "$name$suffix")
        if (!target.exists() || target.length() == 0L) {
            // Written aside then renamed, so a copy cut short by a kill is never taken for a whole one.
            val partial = File(directory, "${target.name}.part")
            openAsset(assetPath).use { input -> partial.outputStream().use { output -> input.copyTo(output) } }
            check(partial.renameTo(target)) { "Cannot write ${target.name}" }
        }
        return target
    }

    private companion object {
        const val DIRECTORY_NAME = "debit-model-cache"
    }
}
