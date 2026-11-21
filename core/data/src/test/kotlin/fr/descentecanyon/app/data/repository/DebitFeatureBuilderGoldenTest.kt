package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.model.DailyWeatherSource
import fr.descentecanyon.app.domain.model.DailyWeatherValue
import fr.descentecanyon.app.domain.model.NeighborCanyon
import fr.descentecanyon.app.domain.model.RainStation
import fr.descentecanyon.app.domain.model.RecentDebitCell
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks that the Kotlin feature pipeline and decision rule reproduce the Python training code.
 * Fixtures are produced by `scripts/debit_model/export_app_assets.py` together with the model.
 */
class DebitFeatureBuilderGoldenTest {

    private val builder = DebitFeatureBuilder()
    private val root: JsonObject by lazy {
        val path = Paths.get("src", "test", "resources", "debit", "golden_vectors.json").toAbsolutePath()
        Json.parseToJsonElement(Files.readAllBytes(path).toString(Charsets.UTF_8)).jsonObject
    }
    private val featureNames: List<String> by lazy { root.getValue("featureNames").jsonArray.map { it.jsonPrimitive.content } }
    private val vectors: List<JsonObject> by lazy { root.getValue("vectors").jsonArray.map { it.jsonObject } }

    @Test
    fun `features match the python reference`() {
        assertTrue("golden vectors must not be empty", vectors.isNotEmpty())
        vectors.forEach { vector ->
            val values = builder.computeFeatureValues(vector.toInput())
            assertNotNull("features unavailable for canyon ${vector.canyonId()}", values)
            val expected = vector.getValue("expected").jsonObject
            featureNames.forEach { name ->
                val expectedValue = expected[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.doubleOrNull
                val actual = values!![name]?.takeUnless { it.isNaN() }
                if (expectedValue == null) {
                    assertEquals("canyon ${vector.canyonId()} $name", null, actual)
                } else {
                    assertNotNull("canyon ${vector.canyonId()} $name missing", actual)
                    val tolerance = 1e-5 * max(1.0, abs(expectedValue))
                    assertEquals("canyon ${vector.canyonId()} $name", expectedValue, actual!!, tolerance)
                }
            }
        }
    }

    @Test
    fun `models and ordinal decision match the python reference`() {
        val thresholdsPath = Paths.get(
            System.getProperty("repoRoot") ?: "..",
            "modele_statistique",
            "thresholds.json",
        ).normalize().toAbsolutePath()
        val thresholdsRoot = Json.parseToJsonElement(Files.readAllBytes(thresholdsPath).toString(Charsets.UTF_8)).jsonObject
        val horizonRoot = thresholdsRoot.getValue("horizonModel").jsonObject
        val todayThresholds = thresholdsRoot.getValue("cumulativeThresholds").jsonArray.map { it.jsonPrimitive.double }
        val horizonThresholds = horizonRoot.getValue("cumulativeThresholds").jsonArray.map { it.jsonPrimitive.double }
        val modelDirectory = Paths.get(System.getProperty("repoRoot") ?: "..", "modele_statistique").normalize().toAbsolutePath()
        fun model(treeAsset: String, network: JsonObject): DebitModel = DebitModel(
            trees = ObliviousTreeModel.read(Files.readAllBytes(modelDirectory.resolve(treeAsset))),
            network = NeuralNetworkModel.read(Files.readAllBytes(modelDirectory.resolve(network.getValue("asset").jsonPrimitive.content))),
            networkWeight = network.getValue("weight").jsonPrimitive.double,
        )
        val today = model("model.trees", thresholdsRoot.getValue("network").jsonObject)
        val horizon = model(horizonRoot.getValue("modelAsset").jsonPrimitive.content, horizonRoot.getValue("network").jsonObject)
        vectors.forEach { vector ->
            val horizonDays = vector["horizon"]?.jsonPrimitive?.int ?: 0
            val model = if (horizonDays == 0) today else horizon
            val thresholds = if (horizonDays == 0) todayThresholds else horizonThresholds
            val values = builder.computeFeatureValues(vector.toInput())!!
            val features = FloatArray(featureNames.size) { index ->
                val value = values[featureNames[index]]
                (if (value == null || value.isNaN()) MISSING else value).toFloat()
            }
            // Model classes are in feature_spec.json "labels" order (SEC … CRUE), as the golden probabilities.
            val distribution = model.predictProbabilities(features)
            val expected = vector.getValue("probabilities").jsonArray.map { it.jsonPrimitive.double }
            distribution.forEachIndexed { index, probability ->
                assertEquals("canyon ${vector.canyonId()} h$horizonDays p$index", expected[index], probability, 2e-4)
            }
            val decision = DebitModelPredictor.decideOrdinal(distribution, thresholds)
            assertEquals("canyon ${vector.canyonId()} level", vector.getValue("level").jsonPrimitive.int, decision.level)
            assertEquals(
                "canyon ${vector.canyonId()} score",
                vector.getValue("displayScore").jsonPrimitive.double,
                decision.displayScore,
                1e-3,
            )
        }
    }

    @Test
    fun `message-only report filter matches the python reference`() {
        val cases = root.getValue("reportQuality").jsonArray.map { it.jsonObject }
        assertTrue("report quality cases must not be empty", cases.isNotEmpty())
        cases.forEach { case ->
            val comment = case.getValue("comment").jsonPrimitive.content
            assertEquals(
                comment.take(120),
                case.getValue("expected").jsonPrimitive.content.toBoolean(),
                DebitReportQuality.isMessageOnlyHighReport(case.getValue("rank").jsonPrimitive.int, false, comment),
            )
        }
    }

    private fun JsonObject.canyonId(): Int = getValue("canyonId").jsonPrimitive.int

    private fun JsonObject.toInput(): DebitFeatureBuilder.DebitFeatureInput {
        val grid = getValue("grid").jsonObject
        val canyon = getValue("canyon").jsonObject
        val lookup = getValue("lookup").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.double }
        return DebitFeatureBuilder.DebitFeatureInput(
            targetDate = LocalDate.parse(getValue("targetDate").jsonPrimitive.content),
            weather = getValue("weather").jsonArray.map { row ->
                val cells = row.jsonArray
                DailyWeatherValue(
                    date = LocalDate.parse(cells[0].jsonPrimitive.content),
                    precipitationSum = cells[1].doubleOrNullValue(),
                    temperature2mMean = cells[2].doubleOrNullValue(),
                    source = DailyWeatherSource.ARCHIVE,
                )
            },
            gridLatitude = grid["latitude"]?.doubleOrNullValue(),
            gridLongitude = grid["longitude"]?.doubleOrNullValue(),
            gridElevation = grid["elevation"]?.doubleOrNullValue(),
            staticValues = getValue("static").jsonObject.mapNotNull { (key, value) ->
                value.doubleOrNullValue()?.let { key to it }
            }.toMap(),
            altitudeDepart = canyon["altitudeDepart"]?.doubleOrNullValue(),
            denivele = canyon["denivele"]?.doubleOrNullValue(),
            longueur = canyon["longueur"]?.doubleOrNullValue(),
            cascadeMax = canyon["cascadeMax"]?.doubleOrNullValue(),
            history = getValue("history").jsonArray.map { pair ->
                pair.jsonArray[0].jsonPrimitive.long to pair.jsonArray[1].jsonPrimitive.int
            },
            massif = DebitFeatureBuilder.MonthlyAggregate.fromLookup(lookup, DebitFeatureBuilder.MASSIF_PREFIX),
            region = DebitFeatureBuilder.MonthlyAggregate.fromLookup(lookup, DebitFeatureBuilder.REGION_PREFIX),
            global = DebitFeatureBuilder.MonthlyAggregate.fromLookup(lookup, DebitFeatureBuilder.GLOBAL_PREFIX),
            neighbors = neighbors(),
            rainStations = get("rainStations")?.jsonArray.orEmpty().map { pair ->
                RainStation(code = pair.jsonArray[0].jsonPrimitive.content, distanceKm = pair.jsonArray[1].jsonPrimitive.double)
            },
            stationRain = get("rainValues")?.jsonObject.orEmpty().mapValues { (_, days) ->
                days.jsonArray.associate { entry ->
                    LocalDate.ofEpochDay(entry.jsonArray[0].jsonPrimitive.long) to entry.jsonArray[1].jsonPrimitive.double
                }
            },
            automaticRain = get("automaticRain")?.jsonObject.orEmpty().mapValues { (_, value) -> value.jsonPrimitive.double },
            horizonDays = get("horizon")?.jsonPrimitive?.int ?: 0,
            recentCells = get("recentCells")?.jsonArray.orEmpty().map { cell ->
                val values = cell.jsonArray
                RecentDebitCell(
                    canyonId = values[0].jsonPrimitive.int,
                    date = LocalDate.ofEpochDay(values[1].jsonPrimitive.long),
                    rank = values[2].jsonPrimitive.int,
                )
            },
        )
    }

    private fun JsonObject.neighbors(): List<NeighborCanyon> {
        val seasonal = get("cellSeasonal")?.jsonObject.orEmpty()
        return get("neighbors")?.jsonArray.orEmpty().map { pair ->
            val id = pair.jsonArray[0].jsonPrimitive.int
            val aggregates = seasonal[id.toString()]?.jsonArray?.map { it.jsonPrimitive.double }
            NeighborCanyon(
                canyonId = id,
                distanceKm = pair.jsonArray[1].jsonPrimitive.double,
                monthlyCounts = aggregates?.subList(0, 12)?.toDoubleArray() ?: DoubleArray(12),
                monthlySums = aggregates?.subList(12, 24)?.toDoubleArray() ?: DoubleArray(12),
            )
        }
    }

    private fun JsonElement.doubleOrNullValue(): Double? = if (this is JsonNull) null else jsonPrimitive.doubleOrNull

    private companion object {
        const val MISSING = -9999.0
    }
}
