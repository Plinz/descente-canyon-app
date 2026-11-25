package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.remote.backend.ServerCanyonPredictionDto
import fr.descentecanyon.app.data.remote.backend.ServerPredictionsDto
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.PredictedDebitLevel
import fr.descentecanyon.app.domain.model.WeatherLocationSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPredictionStoreTest {

    private val directory: File = Files.createTempDirectory("server-predictions").toFile()
    private val json = Json { ignoreUnknownKeys = true }
    private val fixture: ByteArray = javaClass.classLoader!!.getResourceAsStream("backend/predictions.json.gz")!!.use { it.readBytes() }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun store(at: String) = ServerPredictionStore(directory, json, Clock.fixed(Instant.parse(at), ZoneOffset.UTC))

    @Test
    fun `the server's estimate of today is read with its basis and the live check`() {
        store(MORNING).install(fixture, SHA)
        val store = store(MORNING)

        val predictions = checkNotNull(store.predictions(23222))
        assertTrue(predictions.computedOnServer)
        assertEquals("Europe/Paris", predictions.timezone)
        assertEquals(WeatherLocationSource.WATERSHED_CENTER, predictions.target.source)
        assertEquals(Instant.parse("2026-11-25T07:00:00Z"), predictions.computedAt)
        assertEquals(listOf(0, 1, 2), predictions.predictions.map { it.horizonDays })
        val today = predictions.predictions.first()
        assertEquals(LocalDate.of(2026, 11, 25), today.date)
        assertEquals(NiveauDebit.GROS, today.ordinalLevel)
        assertEquals(PredictedDebitLevel.HIGH, today.level)
        assertEquals(1.0, today.levelProbabilities.values.sum(), 1e-3)
        assertEquals(LocalDate.of(2026, 11, 25).minusDays(1133), predictions.basis?.lastReportDate)
        assertEquals(19649L, store.lastReportDay(23222))
        assertNull(store.predictions(1))

        val check = checkNotNull(store.liveCheck())
        assertEquals(LocalDate.of(2026, 8, 27), check.since)
        assertEquals(listOf(40, 38, 0), check.horizons.map { it.reportCount })
        assertEquals(31, check.horizons.first().exactCount)
        assertEquals(5, check.horizons.first().highCaughtCount)
    }

    @Test
    fun `an estimate of an earlier day or older than fourteen hours is not used`() {
        store(MORNING).install(fixture, SHA)

        assertNull(store("2026-11-25T21:30:00Z").predictions(23222))
        assertNull(store("2026-11-25T23:30:00Z").predictions(23222))
        assertNotNull(store("2026-11-25T20:30:00Z").predictions(23222))
    }

    @Test
    fun `a file of another schema is refused and the kept one stays, clear removes it`() {
        val store = store(MORNING)
        store.install(fixture, SHA)
        val other = gzip("""{"schema":2,"computedAt":0}""")

        runCatching { store.install(other, "other") }
        assertEquals(SHA, store.installedSha256())
        assertNotNull(store.predictions(23222))

        store.clear()
        assertNull(store.installedSha256())
        assertNull(store.predictions(23222))
    }

    @Test
    fun `the risk bucket follows the app's decision rule`() {
        fun level(rank: Int, probabilities: List<Double>): PredictedDebitLevel {
            val day = JsonArray(
                listOf(
                    JsonPrimitive("2026-11-25"),
                    JsonPrimitive(0),
                    JsonArray(probabilities.map(::JsonPrimitive)),
                    JsonPrimitive(rank),
                    JsonPrimitive(rank.toDouble()),
                    JsonPrimitive(0.27),
                ),
            )
            val canyon =
                ServerCanyonPredictionDto(tz = "UTC", src = "ENTRY", lat = 45.0, lon = 6.0, lookup = "MASSIF", wx = 0, days = listOf(day))
            val file = ServerPredictionsDto(schema = 1, computedAt = 0, canyons = mapOf("1" to canyon))
            return ServerPredictionStore.toDomain(file, canyon, null).predictions.single().level
        }

        assertEquals(PredictedDebitLevel.LOW, level(1, listOf(0.1, 0.6, 0.2, 0.05, 0.03, 0.02)))
        assertEquals(PredictedDebitLevel.MEDIUM, level(2, listOf(0.05, 0.15, 0.6, 0.15, 0.03, 0.02)))
        // Below GROS but P(GROS or more) above the threshold of the balanced policy.
        assertEquals(PredictedDebitLevel.HIGH, level(2, listOf(0.05, 0.1, 0.55, 0.2, 0.05, 0.05)))
        assertEquals(PredictedDebitLevel.HIGH, level(4, listOf(0.0, 0.0, 0.1, 0.3, 0.4, 0.2)))
    }

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
    }.toByteArray()

    private companion object {
        const val MORNING = "2026-11-25T10:00:00Z"
        const val SHA = "fixture"
    }
}
