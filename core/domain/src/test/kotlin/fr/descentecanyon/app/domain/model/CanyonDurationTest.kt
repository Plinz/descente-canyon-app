package fr.descentecanyon.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanyonDurationTest {

    @Test
    fun `single durations of the sheets`() {
        assertEquals(known(15, 15), CanyonDuration.parse("15min"))
        assertEquals(known(90, 90), CanyonDuration.parse("1h30"))
        assertEquals(known(90, 90), CanyonDuration.parse("1h30min"))
        assertEquals(known(60, 60), CanyonDuration.parse("1h"))
        assertEquals(known(10, 10), CanyonDuration.parse("10min."))
        assertEquals(known(25, 25), CanyonDuration.parse("25m"))
        assertEquals(known(5, 5), CanyonDuration.parse("5"))
        assertEquals(known(2880, 2880), CanyonDuration.parse("2 jours"))
    }

    @Test
    fun `ranges take the unit of the last value when the first has none`() {
        assertEquals(known(20, 45), CanyonDuration.parse("20 à 45min"))
        assertEquals(known(120, 180), CanyonDuration.parse("2-3h"))
        assertEquals(known(240, 360), CanyonDuration.parse("4 - 6h"))
        assertEquals(known(60, 90), CanyonDuration.parse("1h-1h30"))
        assertEquals(known(90, 120), CanyonDuration.parse("1h30/2h"))
        assertEquals(known(45, 60), CanyonDuration.parse("45min/1h"))
        assertEquals(known(90, 120), CanyonDuration.parse("1h30 – 2h"))
        assertEquals(known(5, 45), CanyonDuration.parse("5 ou 45min"))
        assertEquals(known(5, 15), CanyonDuration.parse("15/5min"))
    }

    @Test
    fun `no walk and unknown durations`() {
        assertEquals(CanyonDuration.None, CanyonDuration.parse("néant"))
        assertEquals(CanyonDuration.None, CanyonDuration.parse("0/0min"))
        assertEquals(CanyonDuration.Unknown, CanyonDuration.parse("??"))
        assertEquals(CanyonDuration.Unknown, CanyonDuration.parse("variable"))
        assertEquals(CanyonDuration.Unknown, CanyonDuration.parse(null))
        assertEquals(CanyonDuration.Unknown, CanyonDuration.parse("1h à 2h à 3h"))
    }

    @Test
    fun `a duration cut by the site at ten characters reads its started minutes as tens and leaves out a lost unit`() {
        // "1h15 à 1h45" on the site, kept as "1h15 à 1h4": not 1 h 04 to 1 h 15.
        assertTrue(CanyonDuration.isCut("1h15 à 1h4"))
        assertEquals(known(75, 100), CanyonDuration.parse("1h15 à 1h4"))
        assertEquals(known(90, 120), CanyonDuration.parse("1h30 à 2h0"))
        assertEquals(known(45, 45), CanyonDuration.parse("45min ou 1"))
        assertEquals(known(15, 45), CanyonDuration.parse("15min-45mi"))
        // Ten characters, but complete.
        assertFalse(CanyonDuration.isCut("10 à 25min"))
        assertFalse(CanyonDuration.isCut("30min/1h30"))
        assertFalse(CanyonDuration.isCut("1h à 1h4"))
        assertEquals(known(64, 64), CanyonDuration.parse("1h04"))
    }

    private fun known(min: Int, max: Int) = CanyonDuration.Known(DurationRange(min, max))

    @Test
    fun `the parts of an outing add up unless one is unknown`() {
        assertEquals(
            DurationRange(150, 175),
            CanyonDuration.total(
                CanyonDuration.parse("20 à 45min"),
                CanyonDuration.parse("2h"),
                CanyonDuration.parse("néant"),
                CanyonDuration.parse("10min"),
            ),
        )
        assertEquals(null, CanyonDuration.total(CanyonDuration.parse("30min"), CanyonDuration.parse("??"), CanyonDuration.parse("1h")))
        assertEquals(null, CanyonDuration.total(CanyonDuration.parse("néant"), CanyonDuration.parse("néant")))
    }
}
