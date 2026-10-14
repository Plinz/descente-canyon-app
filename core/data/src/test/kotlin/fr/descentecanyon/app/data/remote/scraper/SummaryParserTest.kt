package fr.descentecanyon.app.data.remote.scraper

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryParserTest {

    private fun loadHtml(name: String): String = javaClass.classLoader!!.getResourceAsStream("html/$name")!!
        .bufferedReader().readText()

    private fun parseValouse() = SummaryParser.parse(Jsoup.parse(loadHtml("canyon_summary.html")), 2186)

    @Test
    fun `parse canyon ID`() {
        assertEquals(2186, parseValouse().id)
    }

    @Test
    fun `parse canyon name`() {
        assertEquals("Valouse", parseValouse().nom)
    }

    @Test
    fun `parse full canyon name`() {
        val result = parseValouse()
        assertTrue(
            "Expected nomComplet to contain 'Valouse', got: ${result.nomComplet}",
            result.nomComplet.contains("Valouse"),
        )
    }

    @Test
    fun `parse country from breadcrumb`() {
        assertEquals("France", parseValouse().pays)
    }

    @Test
    fun `parse departement from breadcrumb`() {
        assertEquals("Ain", parseValouse().departement)
    }

    @Test
    fun `parse cotation`() {
        assertEquals("v4a1III", parseValouse().cotation)
    }

    @Test
    fun `parse altitude depart`() {
        assertEquals(650, parseValouse().altitudeDepart)
    }

    @Test
    fun `parse denivele`() {
        assertEquals(250, parseValouse().denivele)
    }

    @Test
    fun `parse longueur`() {
        assertEquals(250, parseValouse().longueur)
    }

    @Test
    fun `parse cascade max`() {
        assertEquals(68, parseValouse().cascadeMax)
    }

    @Test
    fun `parse corde min`() {
        assertEquals(60, parseValouse().cordeMin)
    }

    @Test
    fun `parse temps approche`() {
        assertEquals("5min", parseValouse().tempsApproche)
    }

    @Test
    fun `parse temps descente`() {
        assertEquals("2h", parseValouse().tempsDescente)
    }

    @Test
    fun `parse navette`() {
        val result = parseValouse()
        assertNotNull(result.navette)
        assertTrue(result.navette!!.contains("8"))
    }

    @Test
    fun `parse interest rating`() {
        val result = parseValouse()
        assertNotNull("Interest should not be null", result.interet)
        assertEquals(2.7f, result.interet!!, 0.1f)
    }

    @Test
    fun `parse nb votes`() {
        assertTrue("Expected votes > 0, got ${parseValouse().nbVotes}", parseValouse().nbVotes > 0)
    }
}
