package fr.descentecanyon.app.ui.map

import com.google.gson.JsonParser
import fr.descentecanyon.app.domain.model.GeoPointType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class MapPointsTest {

    @Test
    fun `one dp covers the world width over 512 at zoom 0 on the equator`() {
        assertEquals(40_075_016.686 / 512, metersPerDp(latitude = 0.0, zoom = 0.0), 0.01)
        // Half as much at 60°, and half again for each zoom level.
        assertEquals(40_075_016.686 / 512 / 2 / 4, metersPerDp(latitude = 60.0, zoom = 2.0), 0.01)
    }

    @Test
    fun `the scale shows the longest round distance that fits`() {
        // 10 m per dp: 960 m fit in 96 dp, so 500 m over 50 dp.
        val (meters, width) = scaleBarStep(metersPerDp = 10.0)
        assertEquals(500.0, meters, 0.0)
        assertEquals(50.0, width, 0.001)
        assertEquals(2_000.0, scaleBarStep(metersPerDp = 25.0).first, 0.0)
        assertEquals(1.0, scaleBarStep(metersPerDp = 0.015).first, 0.0)
    }

    @Test
    fun `equal margins leave the centre where it is`() {
        val center = LatLng(43.0, -0.9)
        val moved = clearCenter(center, zoom = 14.0, padding = MapFitPadding(start = 40f, top = 60f, end = 40f, bottom = 60f))
        assertEquals(center.latitude, moved.latitude, 1e-9)
        assertEquals(center.longitude, moved.longitude, 1e-9)
    }

    @Test
    fun `wider margins at the end and the bottom move the camera east and south`() {
        val center = LatLng(43.0, -0.9)
        val padding = MapFitPadding(start = 28f, top = 84f, end = 76f, bottom = 124f)
        val moved = clearCenter(center, zoom = 14.0, padding = padding)
        // The area then shows left of and above the middle of the screen, clear of the side buttons and the strip.
        assertTrue(moved.longitude > center.longitude)
        assertTrue(moved.latitude < center.latitude)
        val meters = metersPerDp(43.0, 14.0)
        assertEquals(20 * meters / 110_574.0, center.latitude - moved.latitude, 1e-9)
    }

    @Test
    fun `the selected point is drawn on top and its name placed first`() {
        val points = listOf(
            MapPoint(1, 43.01, -0.92, GeoPointType.PARKING_AVAL, "Parking aval"),
            MapPoint(2, 42.99, -0.90, GeoPointType.ENTREE, "Départ du canyon"),
            MapPoint(3, 43.015, -0.926, GeoPointType.SORTIE, "Sortie du canyon"),
        )
        val features = JsonParser.parseString(buildPointGeoJson(points, selectedId = 2)).asJsonObject.getAsJsonArray("features")
        val properties = features.map { it.asJsonObject.getAsJsonObject("properties") }

        assertEquals(listOf(1, 2, 3), properties.map { it.get("pointId").asInt })
        assertEquals("canyon-point-ENTREE-selected", properties[1].get("icon").asString)
        assertEquals("canyon-point-PARKING_AVAL", properties[0].get("icon").asString)
        assertTrue(properties[1].get("selected").asBoolean)
        assertEquals(properties.maxOf { it.get("markerSort").asInt }, properties[1].get("markerSort").asInt)
        assertEquals(properties.minOf { it.get("labelSort").asInt }, properties[1].get("labelSort").asInt)
        assertEquals("#2E7D32", properties[1].get("color").asString)
    }

    @Test
    fun `a point without a name gets no label`() {
        val points = listOf(MapPoint(-1, 43.01, -0.92, GeoPointType.ENTREE, " "))
        val properties = JsonParser.parseString(buildPointGeoJson(points, selectedId = null)).asJsonObject
            .getAsJsonArray("features")[0].asJsonObject.getAsJsonObject("properties")
        assertTrue(!properties.has("label"))
        assertEquals("canyon-point-ENTREE", properties.get("icon").asString)
    }

    @Test
    fun `no point gives an empty collection`() {
        assertEquals(EMPTY_GEOJSON, buildPointGeoJson(emptyList(), selectedId = null))
    }

    @Test
    fun `a new selection or name changes the signature that redraws the points`() {
        val points = listOf(MapPoint(1, 43.01, -0.92, GeoPointType.PARKING_AVAL, "Parking aval"))
        val signature = pointSignature(points, selectedId = null)
        assertTrue(signature != pointSignature(points, selectedId = 1))
        assertTrue(signature != pointSignature(listOf(points[0].copy(label = "Parking")), selectedId = null))
    }
}
