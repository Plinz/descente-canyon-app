package fr.descentecanyon.app.data.repository

/**
 * One embedded debit model: CatBoost trees, averaged with a neural network when one is shipped.
 * Both learners read the same feature vector; [networkWeight] is the share of the network.
 */
class DebitModel(
    private val trees: ObliviousTreeModel,
    private val network: NeuralNetworkModel? = null,
    private val networkWeight: Double = 0.0,
) {
    init {
        require(networkWeight in 0.0..1.0) { "Invalid network weight $networkWeight" }
        require(network == null || (network.featureCount == trees.featureCount && network.classCount == trees.classCount)) {
            "Network and trees do not share the feature contract"
        }
    }

    val featureCount: Int get() = trees.featureCount
    val classCount: Int get() = trees.classCount

    /** One probability row per feature vector, classes in feature_spec.json "labels" order. */
    fun predictProbabilities(featureVectors: List<FloatArray>): List<DoubleArray> = featureVectors.map(::predictProbabilities)

    fun predictProbabilities(features: FloatArray): DoubleArray {
        val fromTrees = trees.predictProbabilities(features)
        val fromNetwork = network?.predictProbabilities(features) ?: return fromTrees
        return DoubleArray(classCount) { c -> (1.0 - networkWeight) * fromTrees[c] + networkWeight * fromNetwork[c] }
    }
}
