package fr.descentecanyon.app.data.repository

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.exp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeModelContractTest {

    private val modelDirectory: Path = Paths.get(System.getProperty("repoRoot") ?: "..", "modele_statistique").normalize().toAbsolutePath()

    @Test
    fun `exported models match the feature contract and give one distribution per row`() {
        val spec = Json.parseToJsonElement(
            Files.readAllBytes(modelDirectory.resolve("feature_spec.json")).toString(Charsets.UTF_8),
        ).jsonObject
        val labels = spec.getValue("labels").jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        val featureCount = spec.getValue("features").jsonArray.size
        val thresholds = Json.parseToJsonElement(
            Files.readAllBytes(modelDirectory.resolve("thresholds.json")).toString(Charsets.UTF_8),
        ).jsonObject
        val horizonAsset = thresholds.getValue("horizonModel").jsonObject.getValue("modelAsset").jsonPrimitive.content

        listOf("model.trees", horizonAsset).forEach { asset ->
            val model = ObliviousTreeModel.read(Files.readAllBytes(modelDirectory.resolve(asset)))
            assertEquals(asset, featureCount, model.featureCount)
            assertEquals(asset, labels.size, model.classCount)
            assertTrue(asset, model.treeCount > 100)

            val rows = model.predictProbabilities(List(3) { row -> FloatArray(featureCount) { (row * 7 + it % 5).toFloat() } })
            assertEquals(3, rows.size)
            rows.forEach { probabilities ->
                assertEquals(labels.size, probabilities.size)
                assertTrue(probabilities.all { it in 0.0..1.0 })
                assertEquals(1.0, probabilities.sum(), 1e-9)
            }
        }
    }

    @Test
    fun `leaf index takes split j as bit j and probabilities are the softmax of scaled leaves`() {
        // One tree, depth 2, 2 classes: split 0 on feature 1 (> 0.5), split 1 on feature 0 (> 10).
        val leaves = floatArrayOf(0f, 0f, 1f, 0f, 0f, 2f, 3f, 3f)
        val model = ObliviousTreeModel.read(
            modelBytes(
                scale = 2.0,
                bias = doubleArrayOf(0.5, 0.0),
                depths = intArrayOf(2),
                features = intArrayOf(1, 0),
                borders = floatArrayOf(0.5f, 10f),
                leaves = leaves,
            ),
        )
        fun expected(leaf: Int): DoubleArray {
            val raw = doubleArrayOf(2.0 * leaves[leaf * 2] + 0.5, 2.0 * leaves[leaf * 2 + 1])
            val sum = raw.sumOf { exp(it) }
            return raw.map { exp(it) / sum }.toDoubleArray()
        }
        assertDistribution(expected(0), model.predictProbabilities(floatArrayOf(5f, 0f)))
        assertDistribution(expected(1), model.predictProbabilities(floatArrayOf(5f, 1f)))
        assertDistribution(expected(2), model.predictProbabilities(floatArrayOf(20f, 0f)))
        assertDistribution(expected(3), model.predictProbabilities(floatArrayOf(20f, 1f)))
        // A value equal to the border goes left, NaN too (CatBoost "Min" treatment).
        assertDistribution(expected(0), model.predictProbabilities(floatArrayOf(10f, 0.5f)))
        assertDistribution(expected(0), model.predictProbabilities(floatArrayOf(Float.NaN, Float.NaN)))
    }

    @Test
    fun `corrupted or mismatched models are rejected`() {
        val valid = modelBytes(1.0, doubleArrayOf(0.0, 0.0), intArrayOf(1), intArrayOf(0), floatArrayOf(0f), floatArrayOf(0f, 0f, 1f, 1f))
        ObliviousTreeModel.read(valid)
        assertThrows(IllegalArgumentException::class.java) { ObliviousTreeModel.read(valid.copyOf(valid.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { ObliviousTreeModel.read(valid + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { ObliviousTreeModel.read(valid.copyOf().also { it[0] = 'X'.code.toByte() }) }
        assertThrows(IllegalArgumentException::class.java) {
            ObliviousTreeModel.read(
                modelBytes(1.0, doubleArrayOf(0.0, 0.0), intArrayOf(1), intArrayOf(3), floatArrayOf(0f), floatArrayOf(0f, 0f, 1f, 1f)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) { ObliviousTreeModel.read(valid).predictProbabilities(FloatArray(3)) }
    }

    private fun assertDistribution(expected: DoubleArray, actual: DoubleArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertEquals(expected[it], actual[it], 1e-12) }
    }

    /** A [FEATURE_COUNT]-feature model in the layout of `scripts/debit_model/tree_model.py`. */
    private fun modelBytes(
        scale: Double,
        bias: DoubleArray,
        depths: IntArray,
        features: IntArray,
        borders: FloatArray,
        leaves: FloatArray,
    ): ByteArray {
        val size = 4 + 4 * 4 + 8 + 8 * bias.size + depths.size + 2 * features.size + 4 * borders.size + 4 * leaves.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("DCOT".toByteArray(Charsets.US_ASCII))
        buffer.putInt(1).putInt(FEATURE_COUNT).putInt(bias.size).putInt(depths.size).putDouble(scale)
        bias.forEach(buffer::putDouble)
        depths.forEach { buffer.put(it.toByte()) }
        features.forEach { buffer.putShort(it.toShort()) }
        borders.forEach(buffer::putFloat)
        leaves.forEach(buffer::putFloat)
        return buffer.array()
    }

    private companion object {
        const val FEATURE_COUNT = 2
    }
}
