package fr.descentecanyon.app.data.remote.scraper

import org.jsoup.Jsoup
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoParserTest {

    private fun loadHtml(name: String): String = javaClass.classLoader!!.getResourceAsStream("html/$name")!!
        .bufferedReader().readText()

    @Test
    fun `parse photos returns non-empty list`() {
        val doc = Jsoup.parse(loadHtml("canyon_photos.html"))
        val result = PhotoParser.parse(doc, 2186)
        assertTrue("Expected photos, got empty list", result.isNotEmpty())
    }

    @Test
    fun `photos have valid URLs`() {
        val doc = Jsoup.parse(loadHtml("canyon_photos.html"))
        val result = PhotoParser.parse(doc, 2186)
        for (photo in result) {
            assertTrue(
                "Photo URL should contain .jpg or descente-canyon: ${photo.url}",
                photo.url.contains(".jpg") || photo.url.contains("descente-canyon"),
            )
        }
    }

    @Test
    fun `photos have canyon ID`() {
        val doc = Jsoup.parse(loadHtml("canyon_photos.html"))
        val result = PhotoParser.parse(doc, 2186)
        assertTrue(result.all { it.canyonId == 2186 })
    }

    @Test
    fun `some photos have authors`() {
        val doc = Jsoup.parse(loadHtml("canyon_photos.html"))
        val result = PhotoParser.parse(doc, 2186)
        val withAuthor = result.count { it.auteur != null }
        assertTrue("Expected some photos with authors, got $withAuthor", withAuthor > 0)
    }
}
