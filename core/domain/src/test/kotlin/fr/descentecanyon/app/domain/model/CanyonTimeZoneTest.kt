package fr.descentecanyon.app.domain.model

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanyonTimeZoneTest {

    private fun zone(country: String?, latitude: Double? = null, longitude: Double? = null) =
        CanyonTimeZone.of(country, latitude, longitude)?.id

    @Test
    fun `mainland France and its overseas canyons each have their zone`() {
        assertEquals("Europe/Paris", zone("France", 44.95, 6.20))
        assertEquals("Indian/Reunion", zone("France", -21.12, 55.47))
        assertEquals("America/Guadeloupe", zone("France", 16.05, -61.66))
        assertEquals("America/Martinique", zone("France", 14.80, -61.17))
        assertEquals("Pacific/Tahiti", zone("France", -17.65, -149.45))
        assertEquals("Pacific/Noumea", zone("France", -21.30, 165.40))
        assertEquals("Indian/Mayotte", zone("France", -12.80, 45.15))
        // No position: mainland.
        assertEquals("Europe/Paris", zone("France"))
    }

    @Test
    fun `islands of Spain and Portugal are told from the mainland`() {
        assertEquals("Atlantic/Canary", zone("Espagne", 28.0, -15.6))
        assertEquals("Europe/Madrid", zone("Espagne", 42.3, 0.1))
        assertEquals("Atlantic/Madeira", zone("Portugal", 32.75, -17.0))
        assertEquals("Atlantic/Azores", zone("Portugal", 37.8, -25.5))
        assertEquals("Europe/Lisbon", zone("Portugal", 41.8, -8.1))
    }

    @Test
    fun `a canyon on a border takes the first country`() {
        assertEquals("Europe/Paris", zone("France , Espagne", 42.8, -0.5))
        assertEquals("Europe/Berlin", zone("Allemagne , Autriche", 47.5, 11.0))
    }

    @Test
    fun `countries of the catalogue have a zone that exists`() {
        listOf("Italie", "Suisse", "Nouvelle Zélande", "Dominique (île)", "Nepal", "Cap-Vert", "Madagascar", "Maurice (île)", "Chine")
            .forEach { country -> ZoneId.of(requireNotNull(zone(country)) { country }) }
        assertEquals("Australia/Sydney", zone("Australie", -33.6, 150.3))
        assertEquals("Australia/Brisbane", zone("Australie", -26.5, 152.7))
        assertEquals("Australia/Hobart", zone("Australie", -42.0, 146.5))
    }

    @Test
    fun `an unknown country has no zone`() {
        assertNull(zone("Atlantide"))
        assertNull(zone(null))
        assertNull(zone(" "))
    }
}
