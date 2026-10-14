package fr.descentecanyon.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTextNormalizerTest {

    @Test
    fun `normalizeForSearch removes accents and neutralizes separators`() {
        assertEquals("cros chaumeil", "  Cròs-Chaumeil  ".normalizeForSearch())
        assertEquals("l infernet", "L'Infernet".normalizeForSearch())
    }
}
