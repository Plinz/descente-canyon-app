package fr.descentecanyon.app.data.remote.weather

import fr.descentecanyon.app.data.network.ApiHttpClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream
import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.DeserializationStrategy
import okhttp3.HttpUrl
import org.junit.After
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
    fun `sums yesterday's hourly rain of every station with the daily convention and estimates the missing hours`() {
        val csv = hourlyCsv {
            // station A: hours ending 07 UTC yesterday .. 03 UTC today (21 hours of 0.5 mm) + one hour before the window
            add("38001001;A;45;5;800;2026101306;9.0;1")
            for (hour in 7..23) add("38001001;A;45;5;800;20261013${"%02d".format(hour)};0.5;1")
            for (hour in 0..3) add("38001001;A;45;5;800;20261014${"%02d".format(hour)};0.5;1")
            // station B: only 5 hours -> incomplete, left out
            for (hour in 19..23) add("38002002;B;45;5;800;20261013${"%02d".format(hour)};1.0;1")
            // station C: Windows line ends, empty values skipped, read whatever its place in the file
            for (hour in 7..23) add("38999999;C;45;5;800;20261013${"%02d".format(hour)};1.0;1\r")
            for (hour in 0..6) add("38999999;C;45;5;800;20261014${"%02d".format(hour)};;1\r")
            add("38999999;C;45;5;800;2026101406;2.0;1\r")
        }

        val table = RainGaugeSource.parseMeteoFranceHourly(
            csv.byteInputStream().bufferedReader().lineSequence(),
            LocalDate.of(2026, 10, 14),
        )

        assertEquals(setOf("38001001", "38999999"), table.keys)
        // 21 hours read: 10.5 mm + 3 missing hours x 0.125 x sqrt(10.5); 18 hours: 19 mm + 6 x 0.125 x sqrt(19).
        assertEquals(10.5 + 3 * 0.125 * sqrt(10.5), table.getValue("38001001").getValue(LocalDate.of(2026, 10, 13)), 1e-9)
        assertEquals(19.0 + 6 * 0.125 * sqrt(19.0), table.getValue("38999999").getValue(LocalDate.of(2026, 10, 13)), 1e-9)
    }

    @Test
    fun `one hourly download serves every canyon of the department`() = runBlocking {
        val client = FakeApiHttpClient(
            mapOf(
                HOURLY_38 to gzip(
                    hourlyCsv {
                        addFullDay("38001001", 0.5)
                        addFullDay("38500001", 1.0)
                    },
                ),
            ),
        )
        val source = source(client, MutableClock(Instant.parse("2026-10-14T10:00:00Z")))

        val first = source.yesterdayAutomaticRain(listOf("38001001"), TODAY)
        val second = source.yesterdayAutomaticRain(listOf("38500001", "38001001"), TODAY)

        assertEquals(mapOf("38001001" to 12.0), first)
        assertEquals(mapOf("38500001" to 24.0, "38001001" to 12.0), second)
        assertEquals(listOf(HOURLY_38), client.requests)
    }

    @Test
    fun `a table fetched before the morning publication is fetched again once it is out`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-10-14T05:00:00Z"))
        // Before 05:44 UTC the file stops before yesterday's hours.
        val client = FakeApiHttpClient(mapOf(HOURLY_38 to gzip(hourlyCsv { add("38001001;A;45;5;800;2026101203;0.5;1") })))
        val source = source(client, clock)

        assertEquals(emptyMap<String, Double>(), source.yesterdayAutomaticRain(listOf("38001001"), TODAY))
        assertEquals(emptyMap<String, Double>(), source.yesterdayAutomaticRain(listOf("38001001"), TODAY))

        client.files[HOURLY_38] = gzip(hourlyCsv { addFullDay("38001001", 0.5) })
        clock.now = Instant.parse("2026-10-14T07:00:00Z")

        assertEquals(mapOf("38001001" to 12.0), source.yesterdayAutomaticRain(listOf("38001001"), TODAY))
        assertEquals(mapOf("38001001" to 12.0), source(client, clock).yesterdayAutomaticRain(listOf("38001001"), TODAY)) // from disk
        assertEquals(listOf(HOURLY_38, HOURLY_38), client.requests)
    }

    @Test
    fun `a download goes on and is kept when the canyon that asked for it is left`() = runBlocking {
        val client = FakeApiHttpClient(mapOf(HOURLY_38 to gzip(hourlyCsv { addFullDay("38001001", 0.5) })))
        val gate = CompletableDeferred<Unit>()
        client.gate = gate
        val source = source(client, MutableClock(Instant.parse("2026-10-14T10:00:00Z")))

        val left = launch(Dispatchers.Default) { source.yesterdayAutomaticRain(listOf("38001001"), TODAY) }
        withTimeout(5_000) { while (client.requests.isEmpty()) delay(5) }
        left.cancelAndJoin()
        gate.complete(Unit)
        val next = withTimeout(5_000) { source.yesterdayAutomaticRain(listOf("38001001"), TODAY) }

        assertEquals(mapOf("38001001" to 12.0), next)
        assertEquals(listOf(HOURLY_38), client.requests)
    }

    private fun source(client: ApiHttpClient, clock: Clock) =
        RainGaugeSource(client, cacheDirectory, clock, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val cacheDirectory: File = Files.createTempDirectory("rain-gauges").toFile()

    @After
    fun deleteCache() {
        cacheDirectory.deleteRecursively()
    }

    private fun hourlyCsv(lines: MutableList<String>.() -> Unit): String =
        (listOf("NUM_POSTE;NOM_USUEL;LAT;LON;ALTI;AAAAMMJJHH;RR1;QRR1") + buildList(lines)).joinToString("\n")

    /** The 24 hours ending 07 UTC on 13/10 .. 06 UTC on 14/10, [rain] mm each. */
    private fun MutableList<String>.addFullDay(station: String, rain: Double) {
        for (hour in 7..23) add("$station;S;45;5;800;20261013${"%02d".format(hour)};$rain;1")
        for (hour in 0..6) add("$station;S;45;5;800;20261014${"%02d".format(hour)};$rain;1")
    }

    private fun gzip(text: String): ByteArray =
        ByteArrayOutputStream().also { bytes -> GZIPOutputStream(bytes).use { it.write(text.toByteArray()) } }.toByteArray()

    private class MutableClock(@Volatile var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    /** Serves [files] by name; a missing file fails like a 404. */
    private class FakeApiHttpClient(initial: Map<String, ByteArray>) : ApiHttpClient {
        val files = ConcurrentHashMap(initial)
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun <T> getJson(url: HttpUrl, deserializer: DeserializationStrategy<T>): T = error("not used")

        override suspend fun getBytes(url: HttpUrl, maxBytes: Long): ByteArray = body(url)

        override suspend fun <T> getStream(url: HttpUrl, read: (InputStream) -> T): T {
            val bytes = body(url)
            gate?.await()
            return read(ByteArrayInputStream(bytes))
        }

        private fun body(url: HttpUrl): ByteArray {
            val name = url.pathSegments.last()
            requests += name
            return files[name] ?: throw IOException("404 $name")
        }
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 10, 14)
        const val HOURLY_38 = "H_38_latest-2025-2026.csv.gz"
    }
}
