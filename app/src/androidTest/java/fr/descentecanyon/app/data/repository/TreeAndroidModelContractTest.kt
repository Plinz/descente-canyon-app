package fr.descentecanyon.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the packaged models (trees and networks) from the APK assets, as the app does. */
class TreeAndroidModelContractTest {

    @Test
    fun packagedModelsMatchTheFeatureContract() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val spec = Json.parseToJsonElement(context.assets.open("feature_spec.json").bufferedReader().use { it.readText() }).jsonObject
        val labels = spec.getValue("labels").jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        val featureCount = spec.getValue("features").jsonArray.size
        assertEquals(EXPECTED_LABELS, labels)
        val thresholds = Json.parseToJsonElement(context.assets.open("thresholds.json").bufferedReader().use { it.readText() }).jsonObject
        val horizon = thresholds.getValue("horizonModel").jsonObject
        val models = listOf(
            "model.trees" to thresholds.getValue("network").jsonObject,
            horizon.getValue("modelAsset").jsonPrimitive.content to horizon.getValue("network").jsonObject,
        )

        models.forEach { (treeAsset, network) ->
            val networkAsset = network.getValue("asset").jsonPrimitive.content
            val model = DebitModel(
                trees = ObliviousTreeModel.read(context.assets.open(treeAsset).use { it.readBytes() }),
                network = NeuralNetworkModel.read(context.assets.open(networkAsset).use { it.readBytes() }),
                networkWeight = network.getValue("weight").jsonPrimitive.double,
            )
            assertEquals(treeAsset, featureCount, model.featureCount)
            assertEquals(treeAsset, labels.size, model.classCount)
            val probabilities = model.predictProbabilities(FloatArray(featureCount))
            assertTrue(treeAsset, probabilities.all { it in 0.0..1.0 })
            assertEquals(treeAsset, 1.0, probabilities.sum(), 1e-9)
        }
    }

    companion object {
        private val EXPECTED_LABELS = listOf("SEC", "FILET", "CORRECT", "GROS", "TRES_GROS", "CRUE")
    }
}
