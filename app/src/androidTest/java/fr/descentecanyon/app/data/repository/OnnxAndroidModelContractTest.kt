package fr.descentecanyon.app.data.repository

import ai.onnxruntime.OnnxMap
import ai.onnxruntime.OnnxSequence
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.FloatBuffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnnxAndroidModelContractTest {

    @Test
    fun androidRuntimeLoadsCatBoostModelAndReadsZipMapProbabilities() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val spec = exportedFeatureSpec(context)
        assertEquals(EXPECTED_LABELS, spec.labels.toSet())

        val modelFile = copyAssetToCacheFile(context, "model.onnx")
        try {
            val environment = OrtEnvironment.getEnvironment()
            val session = environment.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
            session.use {
                assertEquals(EXPECTED_OUTPUTS, session.outputNames)

                val inputName = session.inputNames.single()
                val tensor = OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(FloatArray(spec.featureCount)),
                    longArrayOf(1, spec.featureCount.toLong()),
                )
                tensor.use { inputTensor ->
                    session.run(mapOf(inputName to inputTensor)).use { result ->
                        val runtimeOutputs = result.map { (name, _) -> name }.toSet()
                        assertEquals(EXPECTED_OUTPUTS, runtimeOutputs)

                        var sawZipMapProbabilities = false
                        val probabilities = result.firstNotNullOfOrNull { (name, value) ->
                            if (name == "probabilities") {
                                sawZipMapProbabilities = value is OnnxSequence || value is OnnxMap
                                extractProbabilities(value, spec.labels)
                            } else {
                                null
                            }
                        }
                        assertTrue("CatBoost probabilities should be exposed as ZipMap on Android", sawZipMapProbabilities)
                        assertNotNull(probabilities)
                        assertEquals(EXPECTED_LABELS, probabilities!!.keys)
                        assertTrue(probabilities.values.all { probability -> probability in 0.0..1.0 })
                        assertEquals(1.0, probabilities.values.sum(), 0.0001)
                    }
                }
            }
        } finally {
            modelFile.delete()
        }
    }

    private fun exportedFeatureSpec(context: Context): ExportedFeatureSpec {
        val payload = context.assets.open("feature_spec.json").bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(payload).jsonObject
        return ExportedFeatureSpec(
            labels = root.getValue("labels").jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull },
            featureCount = root.getValue("features").jsonArray.size,
        )
    }

    private fun copyAssetToCacheFile(context: Context, assetName: String): File {
        val target = File(context.cacheDir, "onnx-contract-$assetName")
        context.assets.open(assetName).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private data class ExportedFeatureSpec(val labels: List<String>, val featureCount: Int)

    private fun extractProbabilities(value: OnnxValue, labels: List<String>): Map<String, Double>? = when (value) {
        is OnnxMap -> parseProbabilityMap(value.value)
        is OnnxSequence -> extractProbabilitiesFromRaw(value.value, labels)
        is OnnxTensor -> when (val raw = value.value) {
            is Array<*> -> when (val first = raw.firstOrNull()) {
                is FloatArray -> labels.zip(first.map { it.toDouble() }).toMap()
                is DoubleArray -> labels.zip(first.toList()).toMap()
                else -> null
            }
            is FloatArray -> labels.zip(raw.map { it.toDouble() }).toMap()
            is DoubleArray -> labels.zip(raw.toList()).toMap()
            else -> null
        }
        else -> null
    }

    private fun extractProbabilitiesFromRaw(raw: Any?, labels: List<String>): Map<String, Double>? = when (raw) {
        is OnnxMap -> parseProbabilityMap(raw.value)
        is OnnxValue -> extractProbabilities(raw, labels)
        is Map<*, *> -> parseProbabilityMap(raw)
        is List<*> -> raw.firstNotNullOfOrNull { item -> extractProbabilitiesFromRaw(item, labels) }
        is Array<*> -> raw.firstNotNullOfOrNull { item -> extractProbabilitiesFromRaw(item, labels) }
        else -> null
    }

    private fun parseProbabilityMap(raw: Map<*, *>): Map<String, Double>? {
        val probabilities = raw.entries.mapNotNull { entry ->
            val label = entry.key?.toString() ?: return@mapNotNull null
            val probability = (entry.value as? Number)?.toDouble() ?: return@mapNotNull null
            label to probability
        }.toMap()
        return probabilities.takeIf { it.isNotEmpty() }
    }

    companion object {
        private val EXPECTED_LABELS = setOf("SEC", "FILET", "CORRECT", "GROS", "TRES_GROS", "CRUE")
        private val EXPECTED_OUTPUTS = setOf("label", "probabilities")
    }
}
