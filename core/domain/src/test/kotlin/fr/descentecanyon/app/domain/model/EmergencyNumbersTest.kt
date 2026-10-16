package fr.descentecanyon.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyNumbersTest {

    /** The countries of descente-canyon.com, as the site and the catalog write them (October 2026). */
    private val siteCountries = listOf(
        "France", "Espagne", "Italie", "Suisse", "Autriche", "Portugal", "Grèce", "Allemagne", "Nouvelle Zélande",
        "Slovénie", "Dominique (île)", "Jordanie", "Nepal", "Australie", "Oman", "Maroc", "Cap-Vert", "Chili",
        "Turquie", "Bulgarie", "Monténégro", "Bolivie", "Madagascar", "Andorre", "Venezuela", "Croatie", "Macédoine",
        "Djibouti", "Maurice (île)", "Chine",
    )

    @Test
    fun `every country of the site has its own numbers`() {
        siteCountries.forEach { country ->
            assertTrue(country, EmergencyNumbers.isKnown(country))
            assertTrue(country, EmergencyNumbers.forCountries(country).isNotEmpty())
        }
    }

    @Test
    fun `numbers are the ones of the country, the first one to call first`() {
        assertEquals(listOf("112", "1414", "144", "117", "118"), EmergencyNumbers.forCountries("Suisse").numbers())
        assertEquals(listOf("112", "15", "18", "17"), EmergencyNumbers.forCountries("France , Espagne").numbers())
        assertEquals(listOf("111"), EmergencyNumbers.forCountries("Nouvelle Zélande").numbers())
        // Outside Europe, 112 is not given where it is not the local number.
        assertEquals(listOf("118", "117", "119"), EmergencyNumbers.forCountries("Madagascar").numbers())
        assertEquals(listOf("100", "1144", "102", "101"), EmergencyNumbers.forCountries("Népal").numbers())
        assertEquals(EmergencyService.GENDARMERIE, EmergencyNumbers.forCountries("Maroc").first { it.number == "177" }.service)
        assertEquals(EmergencyService.MOUNTAIN_RESCUE, EmergencyNumbers.forCountries("Chili").first { it.number == "136" }.service)
    }

    @Test
    fun `the mobile network country gives the numbers without a canyon`() {
        assertEquals(listOf("123", "119"), EmergencyNumbers.forIsoCode("CO")?.numbers())
        assertEquals(EmergencyNumbers.forCountries("France"), EmergencyNumbers.forIsoCode("re"))
        assertEquals(listOf("911"), EmergencyNumbers.forIsoCode(" mx ")?.numbers())
        assertNull(EmergencyNumbers.forIsoCode("zz"))
        assertNull(EmergencyNumbers.forIsoCode(null))
    }

    @Test
    fun `an unknown country gets 112 and is flagged`() {
        assertEquals(listOf("112"), EmergencyNumbers.forCountries("Atlantide").numbers())
        assertFalse(EmergencyNumbers.isKnown("Atlantide"))
    }

    @Test
    fun `numbers can be dialled`() {
        (siteCountries.map(EmergencyNumbers::forCountries) + listOf("co", "us", "jp", "th", "za").mapNotNull(EmergencyNumbers::forIsoCode))
            .flatten()
            .forEach { assertTrue(it.number, it.number.matches(Regex("\\d{2,5}"))) }
    }

    private fun List<EmergencyNumber>.numbers(): List<String> = map { it.number }
}
