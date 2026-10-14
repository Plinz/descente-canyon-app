package fr.descentecanyon.app.data.mapper

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DateParserTest {

    // --- ISO format ---

    @Test
    fun `parse standard ISO date`() {
        assertEquals("2026-03-22", DateParser.parseToIsoString("2026-03-22"))
    }

    @Test
    fun `parseToLocalDate with ISO date`() {
        assertEquals(LocalDate.of(2026, 3, 22), DateParser.parseToLocalDate("2026-03-22"))
    }

    // --- French long format: "dim. 22 mars 2026" ---

    @Test
    fun `parse French long date with abbreviated day and dot`() {
        val result = DateParser.parseToIsoString("dim. 22 mars 2026")
        assertEquals("2026-03-22", result)
    }

    @Test
    fun `parse French long date lowercase`() {
        val result = DateParser.parseToIsoString("lun. 1 juin 2025")
        assertEquals("2025-06-01", result)
    }

    @Test
    fun `parse French long date with extra whitespace`() {
        val result = DateParser.parseToIsoString("  mar.  15  janvier  2025  ")
        assertEquals("2025-01-15", result)
    }

    @Test
    fun `parse French long date without dot after day name`() {
        val result = DateParser.parseToIsoString("dim 22 mars 2026")
        assertEquals("2026-03-22", result)
    }

    // --- Short day/month format: "22/03" ---

    @Test
    fun `parse day slash month assumes current year`() {
        val result = DateParser.parseToIsoString("22/03")
        assertNotNull(result)
        val parsed = LocalDate.parse(result!!)
        assertEquals(22, parsed.dayOfMonth)
        assertEquals(3, parsed.monthValue)
        assertEquals(LocalDate.now().year, parsed.year)
    }

    @Test
    fun `parse single digit day slash month`() {
        val result = DateParser.parseToIsoString("5/01")
        assertNotNull(result)
        val parsed = LocalDate.parse(result!!)
        assertEquals(5, parsed.dayOfMonth)
        assertEquals(1, parsed.monthValue)
    }

    @Test
    fun `parse weekday plus day slash month`() {
        val result = DateParser.parseToIsoString("dim. 22/03")
        assertNotNull(result)
        val parsed = LocalDate.parse(result!!)
        assertEquals(22, parsed.dayOfMonth)
        assertEquals(3, parsed.monthValue)
        assertEquals(LocalDate.now().year, parsed.year)
    }

    // --- dd-MM-yyyy format ---

    @Test
    fun `parse dd-MM-yyyy format`() {
        val result = DateParser.parseToIsoString("22-03-2026")
        assertEquals("2026-03-22", result)
    }

    @Test
    fun `parse dd-MM-yyyy another date`() {
        val result = DateParser.parseToIsoString("01-12-2024")
        assertEquals("2024-12-01", result)
    }

    // --- Edge cases / invalid ---

    @Test
    fun `blank string returns null`() {
        assertNull(DateParser.parseToIsoString(""))
        assertNull(DateParser.parseToIsoString("   "))
    }

    @Test
    fun `garbage string returns null`() {
        assertNull(DateParser.parseToIsoString("not-a-date"))
    }

    @Test
    fun `parseToLocalDate with garbage returns null`() {
        assertNull(DateParser.parseToLocalDate("foobar"))
    }

    @Test
    fun `parseToLocalDate with blank returns null`() {
        assertNull(DateParser.parseToLocalDate(""))
    }

    @Test
    fun `parse invalid day-month combination returns null`() {
        assertNull(DateParser.parseToIsoString("31-02-2026"))
    }

    @Test
    fun `French date with accented month`() {
        val result = DateParser.parseToIsoString("mer. 5 février 2026")
        assertEquals("2026-02-05", result)
    }

    @Test
    fun `French date with non-accented month variant`() {
        val result = DateParser.parseToIsoString("sam. 8 aout 2025")
        assertEquals("2025-08-08", result)
    }

    @Test
    fun `French date with abbreviated month`() {
        val result = DateParser.parseToIsoString("dim. 15 janv. 2023")
        assertEquals("2023-01-15", result)
    }

    @Test
    fun `French date with abbreviated accented month`() {
        val result = DateParser.parseToIsoString("ven. 02 août 2024")
        assertEquals("2024-08-02", result)
    }

    @Test
    fun `French date with abbreviated month and non breaking spaces`() {
        val result = DateParser.parseToIsoString("mar. 29\u00A0août\u00A02023")
        assertEquals("2023-08-29", result)
    }

    @Test
    fun `parse French long date with two digit year`() {
        val result = DateParser.parseToIsoString("sam. 8 aout 25")
        assertEquals("2025-08-08", result)
    }

    @Test
    fun `parse day month year with slashes`() {
        val result = DateParser.parseToIsoString("dim. 22/03/2026")
        assertEquals("2026-03-22", result)
    }

    @Test
    fun `parse day month two digit year with slashes`() {
        val result = DateParser.parseToIsoString("31/08/24")
        assertEquals("2024-08-31", result)
    }
}
