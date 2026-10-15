package fr.descentecanyon.app.ui.canyon

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

    @Test
    fun `share text has the name, the place and the link`() {
        assertEquals(
            "Riolan (v4a4III)\nSigale, Aiglun · Alpes-Maritimes · France\nhttps://www.descente-canyon.com/canyoning/canyon/2224/Riolan.html",
            canyonShareText(riolan),
        )
    }

    @Test
    fun `missing fields are skipped`() {
        val canyon = riolan.copy(cotation = "", communes = emptyList(), commune = "", departement = null)

        assertEquals(
            "Riolan\nFrance\nhttps://www.descente-canyon.com/canyoning/canyon/2224/Riolan.html",
            canyonShareText(canyon),
        )
    }
}
