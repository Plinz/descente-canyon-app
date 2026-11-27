package fr.descentecanyon.app.ui.canyon

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationCommunesTest {

    @Test
    fun `the subdivisions the site lists among the communes are left to their own line`() {
        // Chailles: the site lists "Isère, Savoie" before the communes, and writes them "Isère , Savoie".
        assertEquals(
            listOf("Saint-Béron", "Saint-Franc", "Miribel-les-Échelles"),
            locationCommunes(
                listOf("Isère", "Savoie", "Saint-Béron", "Saint-Franc", "Miribel-les-Échelles"),
                "Isère , Savoie",
                "Auvergne-Rhône-Alpes",
            ),
        )
    }

    @Test
    fun `communes alone are kept as they are, in their order`() {
        assertEquals(listOf("Sigale", "Aiglun"), locationCommunes(listOf("Sigale", "Aiglun"), "Alpes-Maritimes", null))
    }

    @Test
    fun `places are compared without their case or spaces`() {
        assertEquals(emptyList<String>(), locationCommunes(listOf(" Jura "), null, "jura"))
    }
}
