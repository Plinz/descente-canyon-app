package fr.descentecanyon.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLinkTest {
    private val links = AppLinks("dc-app.fr")

    @Test
    fun `canyon links carry a readable name the parser ignores`() {
        val url = links.canyon(2895, "Riolan (Clue du)")
        assertEquals("https://dc-app.fr/c/2895-riolan-clue-du", url)
        assertEquals(AppLink.Canyon(2895), links.parse(url!!))
        assertEquals(AppLink.Canyon(2895), links.parse("https://dc-app.fr/c/2895"))
        assertEquals(AppLink.Canyon(2895), links.parse("https://www.dc-app.fr/c/2895-x/"))
        assertEquals("https://dc-app.fr/c/12", links.canyon(12, "   "))
        assertEquals("ecrins-cascade-de-l-oeil", AppLinks.slug("Écrins : cascade de l'Œil"))
    }

    @Test
    fun `sections of a canyon page`() {
        assertEquals("https://dc-app.fr/c/7-a/debits", links.canyon(7, "A", CanyonSection.DEBITS))
        assertEquals(AppLink.Canyon(7, CanyonSection.DEBITS), links.parse("https://dc-app.fr/c/7-a/debits"))
        assertEquals(AppLink.Canyon(7, CanyonSection.CONDITIONS), links.parse("https://dc-app.fr/c/7/Conditions"))
        assertEquals(AppLink.CanyonMap(7), links.parse("https://dc-app.fr/c/7/carte"))
        assertNull(links.parse("https://dc-app.fr/c/7/ailleurs"))
        assertNull(links.parse("https://dc-app.fr/c/7/debits/plus"))
    }

    @Test
    fun `points and tracks of a canyon map`() {
        val point = links.canyonPoint(7, "A", 44.123456, 6.5)
        assertEquals("https://dc-app.fr/c/7-a/carte?lat=44.12346&lon=6.50000", point)
        assertEquals(AppLink.CanyonMap(7, 44.12346, 6.5), links.parse(point!!))
        assertEquals(AppLink.CanyonMap(7), links.parse("https://dc-app.fr/c/7/carte?lat=95&lon=6"))
        val track = links.canyonTrack(7, "A", "tAbc_12-xyZ9")
        assertEquals("https://dc-app.fr/c/7-a/carte?trace=tAbc_12-xyZ9", track)
        assertEquals(AppLink.CanyonMap(7, trackId = "tAbc_12-xyZ9"), links.parse(track!!))
        assertNull(links.canyonTrack(7, "A", "../etc"))
    }

    @Test
    fun `shares and outings carried by the link`() {
        assertEquals("https://dc-app.fr/s/k3F9x2pQa", links.share("k3F9x2pQa"))
        assertEquals(AppLink.Share("k3F9x2pQa"), links.parse("https://dc-app.fr/s/k3F9x2pQa"))
        assertNull(links.share("bad/code"))
        assertNull(links.parse("https://dc-app.fr/s/abc"))
        val outing = links.outingInLink(2895, "Riolan", "eJzLSM3JyVcozy_KSQEAGgQEXQ")
        assertEquals("https://dc-app.fr/o/2895-riolan#eJzLSM3JyVcozy_KSQEAGgQEXQ", outing)
        assertEquals(AppLink.OutingInLink(2895, "eJzLSM3JyVcozy_KSQEAGgQEXQ"), links.parse(outing!!))
        assertEquals(
            AppLink.OutingInLink(2895, "eJzLSM3JyVcozy_KSQEAGgQEXQ"),
            links.parse("https://dc-app.fr/o/2895?p=eJzLSM3JyVcozy_KSQEAGgQEXQ"),
        )
        assertNull(links.parse("https://dc-app.fr/o/2895"))
    }

    @Test
    fun `other hosts, schemes and paths are not the app's`() {
        assertNull(links.parse("https://www.descente-canyon.com/canyoning/canyon/2895/"))
        assertNull(links.parse("http://dc-app.fr/c/2895"))
        assertNull(links.parse("https://dc-app.fr.evil.example/c/2895"))
        assertNull(links.parse("https://dc-app.fr/v1/manifest.json"))
        assertNull(links.parse("https://dc-app.fr/c/0"))
        assertNull(links.parse("not a url"))
        assertNull(AppLinks.NONE.parse("https://dc-app.fr/c/2895"))
        assertNull(AppLinks.NONE.canyon(2895, "Riolan"))
    }
}
