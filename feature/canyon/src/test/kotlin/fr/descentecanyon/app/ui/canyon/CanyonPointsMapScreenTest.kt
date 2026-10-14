package fr.descentecanyon.app.ui.canyon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanyonPointsMapScreenTest {

    @Test
    fun `heading normalization wraps negative and full turns`() {
        assertEquals(350.0, normalizeHeading(-10.0), 0.001)
        assertEquals(10.0, normalizeHeading(370.0), 0.001)
    }

    @Test
    fun `heading smoothing takes the shortest path around north`() {
        val smoothed = smoothHeading(current = 350.0, target = 10.0)

        assertTrue(smoothed > 350.0 || smoothed < 10.0)
        assertTrue(angularDifference(350.0, smoothed) < 10.0)
    }
}
