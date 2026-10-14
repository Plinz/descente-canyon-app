package fr.descentecanyon.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CotationRatingTest {

    @Test
    fun `parse compact cotation`() {
        val rating = CotationRating.parse("v3a2III")

        assertEquals(3, rating.vertical)
        assertEquals(2, rating.aquatic)
        assertEquals(3, rating.engagement)
        assertTrue(rating.isKnown)
    }

    @Test
    fun `parse separated cotation`() {
        val rating = CotationRating.parse("V3/A2/II")

        assertEquals(3, rating.vertical)
        assertEquals(2, rating.aquatic)
        assertEquals(2, rating.engagement)
        assertTrue(rating.isKnown)
    }

    @Test
    fun `unknown cotation stays unparsed`() {
        val rating = CotationRating.parse("??")

        assertFalse(rating.isKnown)
        assertEquals(null, rating.vertical)
    }
}
