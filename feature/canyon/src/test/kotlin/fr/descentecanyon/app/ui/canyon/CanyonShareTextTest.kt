package fr.descentecanyon.app.ui.canyon

import fr.descentecanyon.app.domain.model.AppLinks
import fr.descentecanyon.app.domain.model.Canyon
import org.junit.Assert.assertEquals
import org.junit.Test

class CanyonShareTextTest {

    private val riolan = Canyon(
        id = 2224,
        nom = "Riolan",
        nomComplet = "Riolan",
        pays = "France",
        departement = "Alpes-Maritimes",
        commune = "Aiglun",
        communes = listOf("Sigale", "Aiglun"),
        cotation = "v4a4III",
        url = "https://www.descente-canyon.com/canyoning/canyon/2224/Riolan.html",
    )

    private val links = AppLinks("dc-app.fr")

    @Test
    fun `share text has the name, the place and the app's link`() {
        assertEquals(
            "Riolan (v4a4III)\nSigale, Aiglun · Alpes-Maritimes · France\nhttps://dc-app.fr/c/2224-riolan",
            canyonShareText(riolan, links),
        )
    }

    @Test
    fun `without the app's domain the site's sheet is shared`() {
        assertEquals(
            "Riolan (v4a4III)\nSigale, Aiglun · Alpes-Maritimes · France\nhttps://www.descente-canyon.com/canyoning/canyon/2224/Riolan.html",
            canyonShareText(riolan, AppLinks.NONE),
        )
    }

    @Test
    fun `a canyon added in the app is shared by the app's link`() {
        val added = riolan.copy(id = 1_000_012, url = "")
        assertEquals("https://dc-app.fr/c/1000012-riolan", canyonShareText(added, links).lines().last())
    }

    @Test
    fun `missing fields are skipped`() {
        val canyon = riolan.copy(cotation = "", communes = emptyList(), commune = "", departement = null)

        assertEquals(
            "Riolan\nFrance\nhttps://dc-app.fr/c/2224-riolan",
            canyonShareText(canyon, links),
        )
    }
}
