package fr.descentecanyon.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The link offered between two canyons of an outing, from the chains reviewed over the catalog. */
class CanyonLinkTest {

    @Test
    fun `what the topos say comes first`() {
        // The Cagne: the upper part goes straight on into the lower one.
        assertEquals(CanyonLink.CONTINUOUS, CanyonLink.suggested(CanyonChain(252, 21308, CanyonChain.Way.STRAIGHT_ON), distanceKm = 0.5))
        // The two arms of the Teillon: 45 min on foot from one to the other.
        val teillon = CanyonChain(24100, 24101, CanyonChain.Way.ON_FOOT, minutes = 45)
        assertEquals(CanyonLink.ON_FOOT, CanyonLink.suggested(teillon, distanceKm = 3.0))
    }

    @Test
    fun `without the topos, on foot nearby and by car beyond two kilometres`() {
        assertEquals(CanyonLink.ON_FOOT, CanyonLink.suggested(null, distanceKm = 1.0))
        assertEquals(CanyonLink.BY_CAR, CanyonLink.suggested(null, distanceKm = 13.0))
        // The Furon: the topo forbids going on in the stream, so by the distance only.
        val furon = CanyonChain(26, 27, CanyonChain.Way.NOT_IN_STREAM)
        assertEquals(CanyonLink.BY_CAR, CanyonLink.suggested(furon, distanceKm = 3.0))
    }

    @Test
    fun `the time the topos give holds for their link only`() {
        val versoud = CanyonChain(29, 210, CanyonChain.Way.STRAIGHT_ON, minutes = 180)
        assertEquals(180, versoud.minutesFor(CanyonLink.CONTINUOUS))
        assertNull(versoud.minutesFor(CanyonLink.BY_CAR))
        assertNull(CanyonChain(26, 27, CanyonChain.Way.NOT_IN_STREAM).link)
    }
}
