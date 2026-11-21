package fr.descentecanyon.app.data.repository

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * CatBoost debit model in the compact format written by `scripts/debit_model/tree_model.py`.
 *
 * CatBoost trees are oblivious: each level of a tree tests the same (feature, border) pair, so the
 * leaf of a row is the number whose bit j is `feature > border` for split j of the tree.
 * Probabilities are softmax(scale * sum of leaf values + bias), as `CatBoostClassifier.predict_proba`.
 */
class ObliviousTreeModel private constructor(
    val featureCount: Int,
    val classCount: Int,
    private val scale: Double,
    private val bias: DoubleArray,
    private val depths: IntArray,
    private val splitFeatures: IntArray,
    private val splitBorders: FloatArray,
    private val leafValues: FloatArray,
) {
    val treeCount: Int get() = depths.size

    /** One probability row per feature vector, classes in feature_spec.json "labels" order. */
    fun predictProbabilities(featureVectors: List<FloatArray>): List<DoubleArray> = featureVectors.map(::predictProbabilities)

    fun predictProbabilities(features: FloatArray): DoubleArray {
        require(features.size == featureCount) { "Expected $featureCount features, got ${features.size}" }
        val raw = DoubleArray(classCount)
        var split = 0
        var leaf = 0
        for (depth in depths) {
            var index = 0
            for (level in 0 until depth) {
                // NaN compares false, as CatBoost's default "Min" treatment of missing values.
                if (features[splitFeatures[split + level]] > splitBorders[split + level]) index = index or (1 shl level)
            }
            val offset = leaf + index * classCount
            for (c in 0 until classCount) raw[c] += leafValues[offset + c]
            split += depth
            leaf += (1 shl depth) * classCount
        }
        var max = Double.NEGATIVE_INFINITY
        for (c in 0 until classCount) {
            raw[c] = raw[c] * scale + bias[c]
            if (raw[c] > max) max = raw[c]
        }
        var sum = 0.0
        for (c in 0 until classCount) {
            raw[c] = exp(raw[c] - max)
            sum += raw[c]
        }
        for (c in 0 until classCount) raw[c] /= sum
        return raw
    }

    companion object {
        private val MAGIC = byteArrayOf('D'.code.toByte(), 'C'.code.toByte(), 'O'.code.toByte(), 'T'.code.toByte())
        private const val VERSION = 1
        private const val MAX_DEPTH = 16

        /** Parses a model file; throws [IllegalArgumentException] when it is truncated or inconsistent. */
        fun read(bytes: ByteArray): ObliviousTreeModel = try {
            parse(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
        } catch (truncated: BufferUnderflowException) {
            throw IllegalArgumentException("Truncated tree model", truncated)
        }

        private fun parse(buffer: ByteBuffer): ObliviousTreeModel {
            val magic = ByteArray(MAGIC.size).also(buffer::get)
            require(magic.contentEquals(MAGIC)) { "Not a DCOT tree model" }
            val version = buffer.int
            require(version == VERSION) { "Unsupported tree model version $version" }
            val featureCount = buffer.int
            val classCount = buffer.int
            val treeCount = buffer.int
            require(featureCount > 0 && classCount > 1 && treeCount > 0) { "Invalid tree model header" }
            val scale = buffer.double
            val bias = DoubleArray(classCount) { buffer.double }
            val depths = IntArray(treeCount) { buffer.get().toInt() and 0xFF }
            require(depths.all { it in 1..MAX_DEPTH }) { "Invalid tree depth" }
            val splitCount = depths.sum()
            val splitFeatures = IntArray(splitCount) { buffer.short.toInt() }
            require(splitFeatures.all { it in 0 until featureCount }) { "Split on an unknown feature" }
            val splitBorders = FloatArray(splitCount) { buffer.float }
            val leafCount = depths.sumOf { 1 shl it } * classCount
            val leafValues = FloatArray(leafCount)
            buffer.asFloatBuffer().get(leafValues)
            buffer.position(buffer.position() + leafCount * Float.SIZE_BYTES)
            require(!buffer.hasRemaining()) { "${buffer.remaining()} trailing bytes in tree model" }
            return ObliviousTreeModel(featureCount, classCount, scale, bias, depths, splitFeatures, splitBorders, leafValues)
        }
    }
}
