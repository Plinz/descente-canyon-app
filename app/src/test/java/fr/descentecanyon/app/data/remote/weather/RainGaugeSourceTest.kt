package fr.descentecanyon.app.data.remote.weather

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RainGaugeSourceTest {

    @Test
    fun `parses meteo-france department file and keeps recent days only`() {
        val csv = """
            NUM_POSTE;NOM_USUEL;LAT;LON;ALTI;AAAAMMJJ;RR;QRR;TN
            38001001;POSTE A;45.1;5.6;800;20260901;12.5;1;3.0
            38001001;POSTE A;45.1;5.6;800;20261010;4.2;1;3.0
            38001001;POSTE A;45.1;5.6;800;20261011;;1;3.0
            38002002;POSTE B;45.2;5.7;1200;20261011;0.0;1;1.0
        """.trimIndent()
        val gzipped = ByteArrayOutputStream().also { bytes -> GZIPOutputStream(bytes).use { it.write(csv.toByteArray()) } }.toByteArray()

        val table = RainGaugeSource.parseMeteoFrance(gzipped, LocalDate.of(2026, 10, 1))

        assertEquals(4.2, table.getValue("38001001").getValue(LocalDate.of(2026, 10, 10)), 1e-9)
        assertFalse(table.getValue("38001001").containsKey(LocalDate.of(2026, 9, 1))) // before the kept window
        assertNull(table.getValue("38001001")[LocalDate.of(2026, 10, 11)]) // missing value
        assertEquals(0.0, table.getValue("38002002").getValue(LocalDate.of(2026, 10, 11)), 1e-9)
    }

    @Test
    fun `parses meteoswiss daily file`() {
        val csv = """
            station_abbr;reference_timestamp;rre150d0;rka150d0
            ABE;11.10.2026 00:00;3.4;3.4
            ABE;12.10.2026 00:00;0;0
            ABE;13.10.2026 00:00;;0
        """.trimIndent()

        val values = RainGaugeSource.parseMeteoSwiss(csv)

        assertEquals(mapOf(LocalDate.of(2026, 10, 11) to 3.4, LocalDate.of(2026, 10, 12) to 0.0), values)
    }

    @Test
    fun `department of station codes follows the file naming`() {
        assertEquals("38", RainGaugeSource.departmentOf("38001001"))
        assertEquals("20", RainGaugeSource.departmentOf("20004002"))
        assertEquals("974", RainGaugeSource.departmentOf("97401001"))
    }

    @Test
    fun `sums yesterday's hourly rain with the daily convention and stops after requested stations`() {
        val header = "NUM_POSTE;NOM_USUEL;LAT;LON;ALTI;AAAAMMJJHH;RR1;QRR1"
        val lines = buildList {
            add(header)
            // station A: hours ending 07 UTC yesterday .. 03 UTC today (21 hours of 0.5 mm) + one hour before the window
            add("38001001;A;45;5;800;2026101306;9.0;1")
            for (hour in 7..23) add("38001001;A;45;5;800;20261013${"%02d".format(hour)};0.5;1")
            for (hour in 0..3) add("38001001;A;45;5;800;20261014${"%02d".format(hour)};0.5;1")
            // station B: only 5 hours -> incomplete, left out
            for (hour in 19..23) add("38002002;B;45;5;800;20261013${"%02d".format(hour)};1.0;1")
            // a later station that must not be read
            add("38999999;Z;45;5;800;2026101310;THIS_LINE_IS_NOT_PARSED;1")
        }

        val table = RainGaugeSource.parseMeteoFranceHourly(lines.asSequence(), setOf("38001001", "38002002"), LocalDate.of(2026, 10, 14))

        assertEquals(mapOf("38001001" to mapOf(LocalDate.of(2026, 10, 13) to 10.5)), table)
    }
}
