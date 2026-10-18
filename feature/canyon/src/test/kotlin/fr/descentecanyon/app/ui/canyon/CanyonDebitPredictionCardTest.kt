package fr.descentecanyon.app.ui.canyon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanyonDebitPredictionCardTest {

    @Test
    fun `ordinal gauge maps model cutpoints to equal width visual segments`() {
        val cutpoints = listOf(0.7, 1.5, 2.6, 3.25, 3.75)

        val position = ordinalGaugePositionFraction(score = 2.55, ordinalCutpoints = cutpoints)

        assertTrue(position >= 2f / 6f)
        assertTrue(position < 3f / 6f)
    }

    @Test
    fun `ordinal gauge falls back to legacy equal rank mapping without cutpoints`() {
        assertEquals(2.5f / 6f, ordinalGaugePositionFraction(score = 2.0), 0.0001f)
    }
}
