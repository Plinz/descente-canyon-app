package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.mapper.DateParser
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebitParserTest {

    private fun loadHtml(name: String): String = javaClass.classLoader!!.getResourceAsStream("html/$name")!!
        .bufferedReader().readText()

    @Test
    fun `parse canyon debits returns non-empty list`() {
        val doc = Jsoup.parse(loadHtml("canyon_debits.html"))
        val result = DebitParser.parseCanyonDebits(doc, 2186)
        assertTrue("Expected debits, got empty list", result.isNotEmpty())
    }

    @Test
    fun `canyon debits have correct canyon ID`() {
        val doc = Jsoup.parse(loadHtml("canyon_debits.html"))
        val result = DebitParser.parseCanyonDebits(doc, 2186)
        assertTrue(result.all { it.canyonId == 2186 })
    }

    @Test
    fun `canyon debits have valid niveau`() {
        val validLevels = setOf("CRUE", "TRES_GROS", "GROS", "CORRECT", "FILET", "SEC")
        val doc = Jsoup.parse(loadHtml("canyon_debits.html"))
        val result = DebitParser.parseCanyonDebits(doc, 2186)
        for (debit in result) {
            assertTrue(
                "Invalid niveau: ${debit.niveauRaw}",
                debit.niveauRaw in validLevels,
            )
        }
    }

    @Test
    fun `canyon debits have non-empty date`() {
        val doc = Jsoup.parse(loadHtml("canyon_debits.html"))
        val result = DebitParser.parseCanyonDebits(doc, 2186)
        for (debit in result) {
            assertTrue("Date should not be blank", debit.date.isNotBlank())
        }
    }

    @Test
    fun `canyon debits expose parsable dates`() {
        val doc = Jsoup.parse(loadHtml("canyon_debits.html"))
        val result = DebitParser.parseCanyonDebits(doc, 2186)

        for (debit in result) {
            assertNotNull("Date should be parsable: ${debit.date}", DateParser.parseToLocalDate(debit.date))
        }
    }

    @Test
    fun `merged canyon debit rows are split per author with comments`() {
        val doc = Jsoup.parse(loadHtml("canyon_debits.html"))
        val result = DebitParser.parseCanyonDebits(doc, 2186)
            .filter { it.date == "sam. 19 avril 2025" }

        assertEquals(listOf("ricardcoca", "suze51"), result.mapNotNull { it.auteur })
        assertEquals(2, result.size)
        assertTrue(result.all { !it.commentaire.isNullOrBlank() })
    }

    @Test
    fun `merged canyon debit does not assign a comment to an author without details`() {
        val doc = Jsoup.parse(
            """
            <table id="listedebit"><tbody>
              <tr class="debit4 a2026">
                <td>mer. 19 août 2026</td>
                <td>nec<br />Hodoul</td>
                <td><span title="parcouru"></span></td>
                <td><span class="ic ic-tint"></span></td>
                <td>Douce</td>
                <td>Chaud</td>
                <td><button id="r200716" class="lire">Lire</button></td>
              </tr>
              <tr id="tr200716" class="remarque">
                <td></td>
                <td colspan="6">
                  <div class="userc"><b>Hodoul</b></div>
                  <p>Fait en intégral amont/avale à deux.</p>
                </td>
              </tr>
            </tbody></table>
            """.trimIndent(),
        )

        val result = DebitParser.parseCanyonDebits(doc, 211)

        assertEquals(listOf("nec", "Hodoul"), result.map { it.auteur })
        assertEquals(listOf(null, "Fait en intégral amont/avale à deux."), result.map { it.commentaire })
    }

    @Test
    fun `parse latest debits returns non-empty list`() {
        val doc = Jsoup.parse(loadHtml("derniers_debits.html"))
        val result = DebitParser.parseLatestDebits(doc)
        assertTrue("Expected latest debits, got empty list", result.isNotEmpty())
    }

    @Test
    fun `latest debits have valid canyon IDs`() {
        val doc = Jsoup.parse(loadHtml("derniers_debits.html"))
        val result = DebitParser.parseLatestDebits(doc)
        for (debit in result) {
            assertTrue("Canyon ID should be > 0, got ${debit.canyonId}", debit.canyonId > 0)
        }
    }

    @Test
    fun `latest debits have canyon names`() {
        val doc = Jsoup.parse(loadHtml("derniers_debits.html"))
        val result = DebitParser.parseLatestDebits(doc)
        for (debit in result) {
            assertTrue("Canyon name should not be blank", debit.canyonNom.isNotBlank())
        }
    }

    @Test
    fun `latest debits expose parsable dates`() {
        val doc = Jsoup.parse(loadHtml("derniers_debits.html"))
        val result = DebitParser.parseLatestDebits(doc)

        for (debit in result) {
            assertNotNull("Date should be parsable: ${debit.date}", DateParser.parseToLocalDate(debit.date))
        }
    }

    @Test
    fun `latest debit grid maps day columns to dates and icon classes to ranks`() {
        val doc = Jsoup.parse(loadHtml("derniers_debits_grid.html"))
        val cells = DebitParser.parseLatestDebitGrid(doc)

        // Grid from 29 Sept to 14 Oct 2026; icon d1..d6 = CRUE..SEC (rank 5..0).
        assertEquals(9, cells.size)
        val forronias = cells.single { it.canyonId == 21502 }
        assertEquals(java.time.LocalDate.of(2026, 10, 14), forronias.date)
        assertEquals(2, forronias.rank) // d4 = CORRECT
        val oules = cells.filter { it.canyonId == 2268 }.sortedBy { it.date }
        assertEquals(
            listOf(
                java.time.LocalDate.of(2026, 9, 29) to 2,
                java.time.LocalDate.of(2026, 10, 2) to 2,
                java.time.LocalDate.of(2026, 10, 11) to 3,
                java.time.LocalDate.of(2026, 10, 13) to 3,
            ),
            oules.map { it.date to it.rank },
        )
        assertEquals(0, cells.single { it.canyonId == 2824 }.rank) // d6 = SEC
    }
}
