package fr.descentecanyon.app.data.remote.scraper

import org.jsoup.Jsoup
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoPointParserTest {

    private fun loadHtml(name: String): String = javaClass.classLoader!!.getResourceAsStream("html/$name")!!
        .bufferedReader().readText()

    @Test
    fun `parse geopoints returns non-empty list`() {
        val doc = Jsoup.parse(loadHtml("canyon_carte.html"))
        val result = GeoPointParser.parse(doc)
        assertTrue("Expected geopoints, got empty list", result.isNotEmpty())
    }

    @Test
    fun `geopoints have valid coordinates`() {
        val doc = Jsoup.parse(loadHtml("canyon_carte.html"))
        val result = GeoPointParser.parse(doc)
        for (point in result) {
            assertTrue("Latitude should be > 0, got ${point.latitude}", point.latitude > 0)
            assertTrue("Longitude should be != 0, got ${point.longitude}", point.longitude != 0.0)
        }
    }

    @Test
    fun `geopoints have known types`() {
        val validTypes = setOf(
            "PARKING_AVAL",
            "PARKING_AMONT",
            "ENTREE",
            "SORTIE",
            "POINT_REMARQUABLE",
            "ECHAPPATOIRE",
            "UNKNOWN",
        )
        val doc = Jsoup.parse(loadHtml("canyon_carte.html"))
        val result = GeoPointParser.parse(doc)
        for (point in result) {
            assertTrue(
                "Unknown type: ${point.type}",
                point.type in validTypes,
            )
        }
    }

    @Test
    fun `valouse has parking points`() {
        val doc = Jsoup.parse(loadHtml("canyon_carte.html"))
        val result = GeoPointParser.parse(doc)
        val parkings = result.filter { it.type.startsWith("PARKING") }
        assertTrue("Expected at least 1 parking point", parkings.isNotEmpty())
    }

    @Test
    fun `geopoints extract readable titles from map script`() {
        val doc = Jsoup.parse(loadHtml("canyon_carte.html"))
        val result = GeoPointParser.parse(doc)

        assertTrue(result.any { it.title == "Parking Amont" || it.title == "Parking amont" })
    }

    @Test
    fun `geopoints extract remarks when available`() {
        val doc = Jsoup.parse(
            """
            <html><body><script>
            function initMap() {
              var icons = {
                point_externe: { icon: 'x', title: 'Point remarquable de l\'approche ou du retour' }
              };var markerBounds = new google.maps.LatLngBounds();
              var point = {position: new google.maps.LatLng(45.12,5.42),type: 'point_externe',remarque: 'Passage exposé',auteur: '',date: '1 janv 2025'};addMarker(point);
            }
            </script></body></html>
            """.trimIndent(),
        )
        val result = GeoPointParser.parse(doc)

        assertTrue(result.any { it.remark == "Passage exposé" })
    }
}
