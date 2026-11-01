package fr.descentecanyon.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SpacedDurationTest {

    private fun shown(text: String) = spacedDuration(text).replace(' ', ' ').replace("​", "")

    @Test
    fun `a unit glued to its number is spaced`() {
        assertEquals("10 min", shown("10min"))
        assertEquals("5 h", shown("5h"))
        assertEquals("1 h 30", shown("1h30"))
        assertEquals("2 h 15 min", shown("2h15min"))
        assertEquals("45 mn", shown("45mn"))
    }

    @Test
    fun `ranges and alternatives keep their separators`() {
        assertEquals("1 h 50/2 h 30", shown("1h50/2h30"))
        assertEquals("30-45 min", shown("30-45min"))
        assertEquals("1 h à 1 h 30", shown("1h à 1h30"))
    }

    @Test
    fun `a number keeps its unit on its line`() {
        assertEquals("10 min", spacedDuration("10min"))
        assertEquals("1 h 30", spacedDuration("1h30"))
    }

    @Test
    fun `words and metres are left as written`() {
        assertEquals("3 heures", shown("3 heures"))
        assertEquals("10m", shown("10m"))
    }
}
