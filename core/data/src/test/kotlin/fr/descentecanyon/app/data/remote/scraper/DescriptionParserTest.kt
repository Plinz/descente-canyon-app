package fr.descentecanyon.app.data.remote.scraper

import org.jsoup.Jsoup
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptionParserTest {

    private fun loadHtml(name: String): String = javaClass.classLoader!!.getResourceAsStream("html/$name")!!
        .bufferedReader().readText()

    private fun parseValouse() = DescriptionParser.parse(Jsoup.parse(loadHtml("canyon_description.html")), 2186)

    private fun parseClare() = DescriptionParser.parse(Jsoup.parse(loadHtml("canyon_description_clare.html")), 2477)

    @Test
    fun `parse access aval`() {
        val result = parseValouse()
        val accesAval = requireNotNull(result.accesAval) { "accesAval should not be null" }
        assertTrue(
            "accesAval should mention Burbanche or Hopitaux",
            accesAval.contains("Burbanche") || accesAval.contains("H\u00f4pitaux"),
        )
    }

    @Test
    fun `parse access amont`() {
        val result = parseValouse()
        val accesAmont = requireNotNull(result.accesAmont) { "accesAmont should not be null" }
        assertTrue(
            "accesAmont should mention Tare or plateau",
            accesAmont.contains("Tare") || accesAmont.contains("plateau"),
        )
    }

    @Test
    fun `parse approche`() {
        val result = parseValouse()
        val approche = requireNotNull(result.approche) { "approche should not be null" }
        assertTrue(approche.contains("piste") || approche.contains("cl\u00f4tures"))
    }

    @Test
    fun `parse descente`() {
        val result = parseValouse()
        val descente = requireNotNull(result.descente) { "descente should not be null" }
        assertTrue(descente.contains("cascades") || descente.contains("rappel"))
    }

    @Test
    fun `parse retour`() {
        val result = parseValouse()
        assertNotNull("retour should not be null", result.retour)
    }

    @Test
    fun `parse engagement`() {
        val result = parseValouse()
        val engagement = requireNotNull(result.engagement) { "engagement should not be null" }
        assertTrue(engagement.contains("chappatoire") || engagement.lowercase().contains("echapp"))
    }

    @Test
    fun `parse periode`() {
        val result = parseValouse()
        val periode = requireNotNull(result.periode) { "periode should not be null" }
        assertTrue(periode.contains("sec") || periode.contains("pluie"))
    }

    @Test
    fun `keep full access text when aval amont are not explicit sections`() {
        val result = parseClare()

        val accesAval = requireNotNull(result.accesAval) { "accesAval should keep the full access block" }
        assertTrue(accesAval.startsWith("Par la D 2210"))
        assertTrue(accesAval.contains("(parking aval). Dans cette épingle il y a une piste avec barrière."))
        assertTrue(accesAval.contains("parking du domaine"))
        assertNull("accesAmont should stay null when no explicit amont section exists", result.accesAmont)
    }
}
