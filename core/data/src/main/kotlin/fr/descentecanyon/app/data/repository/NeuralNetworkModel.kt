package fr.descentecanyon.app.data.repository

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Small neural network averaged with the CatBoost trees, in the format written by
 * `scripts/debit_model/network.py` (DCMN v1).
 *
 * Inputs are standardised and clipped to ±6, a missing value counts 0 and sets the missing flag of
 * its feature; hidden layers use GELU (tanh form), the last layer gives the logits of the levels.
 * The probabilities of the members (training seeds) are averaged.
 */
class NeuralNetworkModel private constructor(
    val featureCount: Int,
    val classCount: Int,
    private val missingAt: Float,
    private val mean: FloatArray,
    private val scale: FloatArray,
    private val flagFeatures: IntArray,
    private val widths: IntArray,
    /** weights[member][layer]: row-major (output x input). */
    private val weights: Array<Array<FloatArray>>,
    private val biases: Array<Array<FloatArray>>,
) {
    val memberCount: Int get() = weights.size

    /** One probability row per feature vector, classes in feature_spec.json "labels" order. */
    fun predictProbabilities(featureVectors: List<FloatArray>): List<DoubleArray> = featureVectors.map(::predictProbabilities)

    fun predictProbabilities(features: FloatArray): DoubleArray {
        require(features.size == featureCount) { "Expected $featureCount features, got ${features.size}" }
        val input = DoubleArray(widths[0])
        for (i in 0 until featureCount) {
            if (!isMissing(features[i])) input[i] = ((features[i].toDouble() - mean[i]) / scale[i]).coerceIn(-CLIP, CLIP)
        }
        for ((k, feature) in flagFeatures.withIndex()) {
            if (isMissing(features[feature])) input[featureCount + k] = 1.0
        }
        val probabilities = DoubleArray(classCount)
        for (member in weights.indices) {
            var activations = input
            for (layer in 0 until widths.size - 1) {
                activations = linear(activations, weights[member][layer], biases[member][layer], hidden = layer < widths.size - 2)
            }
            val logits = activations
            val max = logits.max()
            var sum = 0.0
            for (c in 0 until classCount) {
                logits[c] = exp(logits[c] - max)
                sum += logits[c]
            }
            for (c in 0 until classCount) probabilities[c] += logits[c] / sum
        }
        for (c in 0 until classCount) probabilities[c] /= weights.size
        return probabilities
    }

    private fun isMissing(value: Float): Boolean = value.isNaN() || value <= missingAt

    private fun linear(input: DoubleArray, weight: FloatArray, bias: FloatArray, hidden: Boolean): DoubleArray {
        val output = DoubleArray(bias.size)
        for (o in bias.indices) {
            var sum = bias[o].toDouble()
            val row = o * input.size
            for (i in input.indices) sum += weight[row + i] * input[i]
            output[o] = if (hidden) gelu(sum) else sum
        }
        return output
    }

    private fun gelu(x: Double): Double = 0.5 * x * (1.0 + tanh(GELU_SCALE * (x + 0.044715 * x * x * x)))

    companion object {
        private val MAGIC = byteArrayOf('D'.code.toByte(), 'C'.code.toByte(), 'M'.code.toByte(), 'N'.code.toByte())
        private const val VERSION = 1
        private const val CLIP = 6.0
        private const val MAX_LAYERS = 8
        private const val MAX_WIDTH = 4096
        private const val MAX_MEMBERS = 16
        private val GELU_SCALE = sqrt(2.0 / PI)

        /** Parses a network file; throws [IllegalArgumentException] when it is truncated or inconsistent. */
        fun read(bytes: ByteArray): NeuralNetworkModel = try {
            parse(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
        } catch (truncated: BufferUnderflowException) {
            throw IllegalArgumentException("Truncated network model", truncated)
        }

        private fun parse(buffer: ByteBuffer): NeuralNetworkModel {
            val magic = ByteArray(MAGIC.size).also(buffer::get)
            require(magic.contentEquals(MAGIC)) { "Not a DCMN network model" }
            val version = buffer.int
            require(version == VERSION) { "Unsupported network model version $version" }
            val featureCount = buffer.int
            val classCount = buffer.int
            val memberCount = buffer.int
            val layerCount = buffer.int
            require(featureCount > 0 && classCount > 1 && memberCount in 1..MAX_MEMBERS && layerCount in 1..MAX_LAYERS) {
                "Invalid network model header"
            }
            val widths = IntArray(layerCount + 1) { buffer.int }
            require(widths.all { it in 1..MAX_WIDTH } && widths.last() == classCount) { "Invalid layer widths" }
            val missingAt = buffer.float
            val mean = FloatArray(featureCount) { buffer.float }
            val scale = FloatArray(featureCount) { buffer.float }
            require(mean.all { it.isFinite() } && scale.all { it.isFinite() && it > 0f }) { "Invalid input scaling" }
            val flagCount = buffer.int
            require(flagCount in 0..featureCount && widths[0] == featureCount + flagCount) { "Invalid missing flags" }
            val flagFeatures = IntArray(flagCount) { buffer.int }
            require(flagFeatures.all { it in 0 until featureCount } && flagFeatures.asList().zipWithNext().all { (a, b) -> a < b }) {
                "Missing flag on an unknown feature"
            }
            val weights = Array(memberCount) { Array(layerCount) { FloatArray(0) } }
            val biases = Array(memberCount) { Array(layerCount) { FloatArray(0) } }
            for (member in 0 until memberCount) {
                for (layer in 0 until layerCount) {
                    weights[member][layer] = buffer.floats(widths[layer + 1] * widths[layer])
                    biases[member][layer] = buffer.floats(widths[layer + 1])
                }
            }
            require(!buffer.hasRemaining()) { "${buffer.remaining()} trailing bytes in network model" }
            return NeuralNetworkModel(featureCount, classCount, missingAt, mean, scale, flagFeatures, widths, weights, biases)
        }

        private fun ByteBuffer.floats(count: Int): FloatArray {
            val values = FloatArray(count)
            asFloatBuffer().get(values)
            position(position() + count * Float.SIZE_BYTES)
            return values
        }
    }
}
