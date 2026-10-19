package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.SiteObservation
import java.time.LocalDate
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteObservationsParserTest {

    private val page = Jsoup.parse(requireNotNull(javaClass.getResource("/html/perso_observations.html")).readText())

    @Test
    fun `reads each report of the member with its remarks and canyon`() {
        val observations = SiteObservationsParser.parse(page, today = LocalDate.of(2026, 10, 19))

        assertEquals(
            SiteObservation(
                id = 199338,
                canyonId = 22146,
                canyonName = "Saut du Laïre",
                date = LocalDate.of(2026, 7, 30),
                isDescended = true,
                debitLevel = NiveauDebit.GROS,
                waterTemperature = "Froide",
                airTemperature = "Chaud",
                publicRemark = "Approche 1h10, belle rando.\nOn peut sauter de plusieurs vasques après avoir sondé.",
                personalComment = "Avec Paul et Léa\ncorde 2x40",
            ),
            observations[0],
        )
        assertEquals(listOf(199338L, 195489L, 120001L), observations.map { it.id })
    }

    @Test
    fun `tells descents from flows only looked at, and reads every level`() {
        val observations = SiteObservationsParser.parse(page, today = LocalDate.of(2026, 10, 19))

        assertEquals(listOf(true, false, true), observations.map { it.isDescended })
        assertEquals(listOf(NiveauDebit.GROS, NiveauDebit.CRUE, NiveauDebit.SEC), observations.map { it.debitLevel })
        assertEquals(listOf(22146, 2283, 2554), observations.map { it.canyonId })
        assertEquals(null, observations[1].waterTemperature)
        assertEquals(null, observations[2].publicRemark)
        assertEquals(LocalDate.of(2018, 7, 29), observations[2].date)
    }

    @Test
    fun `the login form is not the list`() {
        assertTrue(SiteObservationsParser.isObservationsPage(page))
        assertFalse(SiteObservationsParser.isObservationsPage(Jsoup.parse("<html><body><form action=\"/login\"></form></body></html>")))
        assertEquals(emptyList<SiteObservation>(), SiteObservationsParser.parse(Jsoup.parse("<html><body></body></html>")))
    }
}
