package fr.descentecanyon.app.data.local.catalog

import android.app.Application
import app.cash.turbine.test
import fr.descentecanyon.app.data.remote.backend.BackendTestServer
import fr.descentecanyon.app.testutil.TestDatabases
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** catalog-bundle-21.json is a real bundle of the APK's catalog made by server/tools/make_app_fixtures.py. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CatalogBundleApplierTest {

    private val database = TestDatabases.catalog()
    private val applier = CatalogBundleApplier(database)
    private val forbidden = Json.parseToJsonElement(BackendTestServer.resource("backend/catalog-bundle-21.json")).jsonObject

    @After
    fun tearDown() = database.close()

    @Test
    fun `a bundle replaces every row of its canyon, at once and visibly`() = runTest {
        applier.apply(CatalogChanges(listOf(previousVersionOf(forbidden), otherCanyon()), emptyList(), emptyMap()))
        assertFalse(database.canyonDao().getById(21)!!.isForbidden)
        assertEquals(5, database.geoPointDao().getByCanyonId(21).size)

        database.searchIndexDao().observeAll().test {
            awaitItem()
            applier.apply(CatalogChanges(listOf(forbidden), emptyList(), mapOf(21 to 1_795_572_000_000L, 22 to 1L)))
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        val canyon = database.canyonDao().getById(21)!!
        assertTrue(canyon.isForbidden)
        assertEquals("Canyon interdit par arrêté.", canyon.remarques)
        assertEquals(1_795_572_000_000L, canyon.lastUpdated)
        assertEquals(4, database.geoPointDao().getByCanyonId(21).size)
        assertEquals(5, database.bibliographyDao().getByCanyonId(21).size)
        // Another canyon keeps its rows, and an older reading time never goes back.
        assertEquals(1, database.geoPointDao().getByCanyonId(22).size)
        assertEquals(1_000L, database.canyonDao().getById(22)!!.lastUpdated)
    }

    @Test
    fun `applying the same bundle twice changes nothing, a removed canyon loses all its rows`() = runTest {
        applier.apply(CatalogChanges(listOf(forbidden, otherCanyon()), emptyList(), emptyMap()))
        applier.apply(CatalogChanges(listOf(forbidden), emptyList(), emptyMap()))
        assertEquals(4, database.geoPointDao().getByCanyonId(21).size)

        applier.apply(CatalogChanges(emptyList(), listOf(21), emptyMap()))

        assertNull(database.canyonDao().getById(21))
        assertEquals(0, database.geoPointDao().getByCanyonId(21).size)
        assertEquals(1, database.searchIndexDao().count())
    }

    /** The same canyon before the site forbade it: one more point, not forbidden. */
    private fun previousVersionOf(bundle: JsonObject): JsonObject {
        val tables = bundle.getValue("tables").jsonObject.toMutableMap()
        val canyon = tables.getValue("canyons").jsonArray.single().jsonObject.toMutableMap()
        canyon["isForbidden"] = JsonPrimitive(0)
        canyon["remarques"] = JsonPrimitive("Belle course.")
        tables["canyons"] = JsonArray(listOf(JsonObject(canyon)))
        val point = tables.getValue("geo_points").jsonArray.first().jsonObject.toMutableMap()
        point["type"] = JsonPrimitive("POINT_REMARQUABLE")
        tables["geo_points"] = JsonArray(tables.getValue("geo_points").jsonArray + JsonObject(point))
        return JsonObject(bundle + ("tables" to JsonObject(tables)))
    }

    /** Canyon 22 with one point, written by the same path (its reading time: 1000). */
    private fun otherCanyon(): JsonObject {
        val tables = forbidden.getValue("tables").jsonObject
        fun rowOf(table: String, vararg changes: Pair<String, Any>) = JsonObject(
            tables.getValue(table).jsonArray.first().jsonObject +
                changes.associate { (k, v) -> k to (if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())) },
        )
        return JsonObject(
            mapOf(
                "bundle" to JsonPrimitive(1),
                "canyonId" to JsonPrimitive(22),
                "tables" to JsonObject(
                    mapOf(
                        "canyons" to JsonArray(listOf(rowOf("canyons", "id" to 22, "nom" to "Pellas", "lastUpdated" to 1_000L))),
                        "geo_points" to JsonArray(listOf(rowOf("geo_points", "canyonId" to 22))),
                        "search_index" to JsonArray(listOf(rowOf("search_index", "id" to 22, "nom" to "Pellas"))),
                    ),
                ),
            ),
        )
    }
}
