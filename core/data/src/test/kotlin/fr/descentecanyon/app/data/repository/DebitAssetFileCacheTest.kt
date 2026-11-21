package fr.descentecanyon.app.data.repository

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebitAssetFileCacheTest {

    private val directory: File = Files.createTempDirectory("debit-model-cache").toFile()
    private val assets = mapOf(
        "runtime_feature_lookups.canyons.json" to "lookups",
        "canyon_static_features.json" to "static",
    )
    private val opened = mutableListOf<String>()

    private fun cache(versionCode: Int = 57) = DebitAssetFileCache(directory, versionCode) { path ->
        opened += path
        ByteArrayInputStream(assets.getValue(path).toByteArray())
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `a second store's first copy keeps the copy the first store is reading`() {
        // Order of a prediction: runtime lookups (support), then static features (model store).
        val cache = cache()
        val lookups = cache.file("runtime_feature_lookups.canyons.json", "runtime-lookups-canyons")
        val static = cache.file("canyon_static_features.json", "static-features")

        assertTrue(lookups.exists())
        assertEquals("lookups", lookups.readText())
        assertEquals("static", static.readText())
    }

    @Test
    fun `copies of older versions, ONNX models and interrupted copies are removed`() {
        val stale = listOf(
            "runtime-lookups-canyons-v56.json",
            "static-features-v56.json",
            "model-v57.onnx",
            "static-features-v57.json.part",
        )
            .map { name -> File(directory, name).apply { writeText("old") } }

        cache().file("canyon_static_features.json", "static-features")

        stale.forEach { assertFalse(it.name, it.exists()) }
    }

    @Test
    fun `a whole copy of this version is reused, an empty one is written again`() {
        File(directory, "static-features-v57.json").writeText("static")
        File(directory, "runtime-lookups-canyons-v57.json").createNewFile()

        val cache = cache()
        cache.file("canyon_static_features.json", "static-features")
        val lookups = cache.file("runtime_feature_lookups.canyons.json", "runtime-lookups-canyons")

        assertEquals(listOf("runtime_feature_lookups.canyons.json"), opened)
        assertEquals("lookups", lookups.readText())
    }
}
