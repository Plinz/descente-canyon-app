package fr.descentecanyon.app.data.repository

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralNetworkModelTest {

    private val modelDirectory: Path = Paths.get(System.getProperty("repoRoot") ?: "..", "modele_statistique").normalize().toAbsolutePath()

    @Test
    fun `exported networks match the feature contract and give one distribution per row`() {
        val spec = json("feature_spec.json")
        val labels = spec.getValue("labels").jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        val featureCount = spec.getValue("features").jsonArray.size
        val thresholds = json("thresholds.json")
        val networks = listOf(
            thresholds.getValue("network").jsonObject,
            thresholds.getValue("horizonModel").jsonObject.getValue("network").jsonObject,
        )

        networks.forEach { entry ->
            val asset = entry.getValue("asset").jsonPrimitive.content
            assertTrue(asset, entry.getValue("weight").jsonPrimitive.double in 0.0..1.0)
            val model = NeuralNetworkModel.read(Files.readAllBytes(modelDirectory.resolve(asset)))
            assertEquals(asset, featureCount, model.featureCount)
            assertEquals(asset, labels.size, model.classCount)
            assertTrue(asset, model.memberCount >= 1)
            val vectors = List(3) { row -> FloatArray(featureCount) { if (it % 4 == row) MISSING else (row * 7 + it % 5).toFloat() } }
            val rows = model.predictProbabilities(vectors)
            rows.forEach { probabilities ->
                assertEquals(labels.size, probabilities.size)
                assertTrue(probabilities.all { it in 0.0..1.0 })
                assertEquals(1.0, probabilities.sum(), 1e-9)
            }
        }
    }

    @Test
    fun `inputs are standardised with missing flags then go through gelu layers and a softmax`() {
        // 2 features (flag on feature 1), hidden layer of 2 units, 2 classes, 2 members.
        val first = listOf(
            floatArrayOf(1f, -2f, 0.5f, 0.3f, 0.7f, 1f) to floatArrayOf(0.1f, -0.2f),
            floatArrayOf(1f, -1f, 0.5f, -1f) to floatArrayOf(0.05f, 0f),
        )
        val second = listOf(
            floatArrayOf(-1f, 0.5f, 2f, 1f, 0f, -1f) to floatArrayOf(0f, 0.3f),
            floatArrayOf(0.2f, 1f, -0.4f, 2f) to floatArrayOf(0f, 0.1f),
        )
        val model = NeuralNetworkModel.read(
            networkBytes(
                mean = floatArrayOf(10f, 1f),
                scale = floatArrayOf(2f, 4f),
                flags = intArrayOf(1),
                members = listOf(first, second),
            ),
        )

        fun expected(features: FloatArray): DoubleArray {
            val missing = features.map { it.isNaN() || it <= MISSING_AT }
            val input = doubleArrayOf(
                if (missing[0]) 0.0 else ((features[0] - 10.0) / 2.0).coerceIn(-6.0, 6.0),
                if (missing[1]) 0.0 else ((features[1] - 1.0) / 4.0).coerceIn(-6.0, 6.0),
                if (missing[1]) 1.0 else 0.0,
            )
            val average = DoubleArray(2)
            listOf(first, second).forEach { layers ->
                val hidden = DoubleArray(2) { o ->
                    gelu(layers[0].second[o] + (0 until 3).sumOf { i -> layers[0].first[o * 3 + i] * input[i] })
                }
                val logits = DoubleArray(2) { o -> layers[1].second[o] + (0 until 2).sumOf { i -> layers[1].first[o * 2 + i] * hidden[i] } }
                val sum = logits.sumOf { exp(it) }
                logits.forEachIndexed { c, logit -> average[c] += exp(logit) / sum / 2 }
            }
            return average
        }
        listOf(
            floatArrayOf(12f, 3f),
            floatArrayOf(9f, MISSING),
            floatArrayOf(Float.NaN, -7f),
            floatArrayOf(1000f, Float.NaN),
        ).forEach { features -> assertDistribution(expected(features), model.predictProbabilities(features)) }
    }

    @Test
    fun `the debit model averages trees and network with the network weight`() {
        val network = NeuralNetworkModel.read(
            networkBytes(
                mean = floatArrayOf(0f, 0f, 0f),
                scale = floatArrayOf(1f, 1f, 1f),
                flags = intArrayOf(),
                members = listOf(listOf(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f) to floatArrayOf(0f, 0f))),
            ),
        )
        val trees = ObliviousTreeModel.read(Files.readAllBytes(treeFixture()))
        val features = floatArrayOf(2f, 0f, 0f)
        val fromTrees = trees.predictProbabilities(features)
        val fromNetwork = network.predictProbabilities(features)
        val blended = DebitModel(trees, network, 0.25).predictProbabilities(features)
        blended.indices.forEach { assertEquals(0.75 * fromTrees[it] + 0.25 * fromNetwork[it], blended[it], 1e-12) }
        assertDistribution(fromTrees, DebitModel(trees).predictProbabilities(features))
        assertThrows(IllegalArgumentException::class.java) { DebitModel(trees, network, 1.5) }
    }

    @Test
    fun `corrupted or mismatched networks are rejected`() {
        val layers = listOf(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f) to floatArrayOf(0f, 0f))
        val valid = networkBytes(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f), intArrayOf(1), listOf(layers))
        NeuralNetworkModel.read(valid)
        assertThrows(IllegalArgumentException::class.java) { NeuralNetworkModel.read(valid.copyOf(valid.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { NeuralNetworkModel.read(valid + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { NeuralNetworkModel.read(valid.copyOf().also { it[0] = 'X'.code.toByte() }) }
        // Flag on an unknown feature, zero scale.
        assertThrows(IllegalArgumentException::class.java) {
            NeuralNetworkModel.read(networkBytes(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f), intArrayOf(2), listOf(layers)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NeuralNetworkModel.read(networkBytes(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), intArrayOf(1), listOf(layers)))
        }
        assertThrows(IllegalArgumentException::class.java) { NeuralNetworkModel.read(valid).predictProbabilities(FloatArray(3)) }
    }

    private fun json(name: String) =
        Json.parseToJsonElement(Files.readAllBytes(modelDirectory.resolve(name)).toString(Charsets.UTF_8)).jsonObject

    private fun gelu(x: Double): Double = 0.5 * x * (1.0 + tanh(sqrt(2.0 / PI) * (x + 0.044715 * x * x * x)))

    private fun assertDistribution(expected: DoubleArray, actual: DoubleArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertEquals(expected[it], actual[it], 1e-9) }
    }

    /** A 3-feature, 2-class tree model with one depth-1 tree (split on feature 0 > 1). */
    private fun treeFixture(): Path {
        val buffer = ByteBuffer.allocate(4 + 16 + 8 + 16 + 1 + 2 + 4 + 16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("DCOT".toByteArray(Charsets.US_ASCII))
        buffer.putInt(1).putInt(3).putInt(2).putInt(1).putDouble(1.0)
        buffer.putDouble(0.0).putDouble(0.0)
        buffer.put(1.toByte()).putShort(0).putFloat(1f)
        floatArrayOf(0f, 1f, 2f, 0f).forEach(buffer::putFloat)
        return Files.createTempFile("trees", ".trees").also { Files.write(it, buffer.array()) }
    }

    /**
     * A network in the layout of `scripts/debit_model/network.py`; each member is a list of
     * (row-major weights, biases) per layer, the input width being features + flags.
     */
    private fun networkBytes(
        mean: FloatArray,
        scale: FloatArray,
        flags: IntArray,
        members: List<List<Pair<FloatArray, FloatArray>>>,
    ): ByteArray {
        val layers = members.first()
        val widths = listOf(mean.size + flags.size) + layers.map { it.second.size }
        val floats = members.sumOf { member -> member.sumOf { it.first.size + it.second.size } }
        val size = 4 + 5 * 4 + 4 * widths.size + 4 + 8 * mean.size + 4 + 4 * flags.size + 4 * floats
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("DCMN".toByteArray(Charsets.US_ASCII))
        buffer.putInt(1).putInt(mean.size).putInt(widths.last()).putInt(members.size).putInt(layers.size)
        widths.forEach(buffer::putInt)
        buffer.putFloat(MISSING_AT)
        mean.forEach(buffer::putFloat)
        scale.forEach(buffer::putFloat)
        buffer.putInt(flags.size)
        flags.forEach(buffer::putInt)
        members.forEach { member ->
            member.forEach { (weights, biases) ->
                weights.forEach(buffer::putFloat)
                biases.forEach(buffer::putFloat)
            }
        }
        return buffer.array()
    }

    private companion object {
        const val MISSING = -9999f
        const val MISSING_AT = -9998f
    }
}
