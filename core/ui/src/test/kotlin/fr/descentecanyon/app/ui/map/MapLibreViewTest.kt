package fr.descentecanyon.app.ui.map

import fr.descentecanyon.app.domain.model.CanyonSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLibreViewTest {

    @Test
    fun `detail points are shown when zoom reaches threshold`() {
        assertTrue(shouldShowDetailPoints(zoom = 8.4, visibleMarkerCount = 30))
    }

    @Test
    fun `detail points are shown when few canyons are visible`() {
        assertTrue(shouldShowDetailPoints(zoom = 7.2, visibleMarkerCount = 3))
    }

    @Test
    fun `clusters stay active when zoom is low and many canyons are visible`() {
        assertFalse(shouldShowDetailPoints(zoom = 7.2, visibleMarkerCount = 20))
    }

    @Test
    fun `cluster tap advances by at least one and a half zoom levels`() {
        assertEquals(4.5, clusterTapTargetZoom(currentZoom = 3.0, expansionZoom = 4.0), 0.001)
    }

    @Test
    fun `cluster tap honors a farther expansion zoom`() {
        assertEquals(6.0, clusterTapTargetZoom(currentZoom = 3.0, expansionZoom = 6.0), 0.001)
    }

    @Test
    fun `cluster tap advances two zoom levels when the source cannot expand`() {
        assertEquals(7.0, clusterTapTargetZoom(currentZoom = 5.0, expansionZoom = null), 0.001)
    }

    @Test
    fun `interest marker colors follow descente canyon scale`() {
        assertEquals(0xFFE7F3F5.toInt(), interestMarkerColor(canyonWithInterest(null)))
        assertEquals(0xFFE7F3F5.toInt(), interestMarkerColor(canyonWithInterest(0f)))
        assertEquals(0xFFE08A3D.toInt(), interestMarkerColor(canyonWithInterest(0.5f)))
        assertEquals(0xFFFFC857.toInt(), interestMarkerColor(canyonWithInterest(1.5f)))
        assertEquals(0xFF3DAA68.toInt(), interestMarkerColor(canyonWithInterest(2.5f)))
        assertEquals(0xFF0077E6.toInt(), interestMarkerColor(canyonWithInterest(3.5f)))
        assertEquals(0xFF0077E6.toInt(), interestMarkerColor(canyonWithInterest(4f)))
    }

    @Test
    fun `interest render priority draws high interest canyons last`() {
        assertEquals(0, interestMarkerRenderPriority(canyonWithInterest(3.5f, isForbidden = true)))
        assertEquals(1, interestMarkerRenderPriority(canyonWithInterest(null)))
        assertEquals(2, interestMarkerRenderPriority(canyonWithInterest(0.5f)))
        assertEquals(3, interestMarkerRenderPriority(canyonWithInterest(1.5f)))
        assertEquals(4, interestMarkerRenderPriority(canyonWithInterest(2.5f)))
        assertEquals(5, interestMarkerRenderPriority(canyonWithInterest(3.5f)))
    }

    private fun canyonWithInterest(interest: Float?, isForbidden: Boolean = false): CanyonSummary = CanyonSummary(
        id = 1,
        nom = "Test",
        pays = "FR",
        cotation = "3/3/II",
        interet = interest,
        url = "https://example.test/canyon/1",
        isForbidden = isForbidden,
    )
}
