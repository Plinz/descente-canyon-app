package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.DailyDebitPrediction
import fr.descentecanyon.app.domain.model.DebitPredictionPolicy
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.PredictedDebitLevel
import fr.descentecanyon.app.domain.model.RuntimeLookupSource
import fr.descentecanyon.app.domain.model.WeatherLocationSource
import fr.descentecanyon.app.domain.model.WeatherTarget
import fr.descentecanyon.app.testutil.InMemoryKeyValueDao
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionCacheTest {

    private val keyValueDao = InMemoryKeyValueDao()
    private val computedAt = Instant.parse("2026-07-14T06:00:00Z")

    @Test
    fun `the next day the estimate of yesterday is shown for today and tomorrow`() = runTest {
        PredictionCache(keyValueDao, clockAt(computedAt)).save(42, predictions())

        val nextDay = PredictionCache(keyValueDao, clockAt(computedAt.plusSeconds(86_400)))
        val cached = nextDay.read(42)!!

        assertTrue(cached.isFromCache)
        assertEquals(computedAt, cached.computedAt)
        assertEquals(listOf(0, 1), cached.predictions.map { it.horizonDays })
        assertEquals(LocalDate.of(2026, 7, 15), cached.predictions.first().date)
        val today = cached.predictions.first()
        assertEquals(NiveauDebit.GROS, today.ordinalLevel)
        assertEquals(0.4, today.levelProbabilities.getValue(NiveauDebit.GROS), 1e-9)
        assertEquals(0.31, today.probabilities.getValue(PredictedDebitLevel.HIGH), 1e-9)
        assertEquals(WeatherLocationSource.WATERSHED_CENTER, cached.target.source)
        assertEquals(DebitPredictionPolicy.PRUDENT, cached.policy)
    }

    @Test
    fun `an estimate older than two days or of another canyon is not shown`() = runTest {
        PredictionCache(keyValueDao, clockAt(computedAt)).save(42, predictions())

        assertNull(PredictionCache(keyValueDao, clockAt(computedAt.plusSeconds(3 * 86_400))).read(42))
        assertNull(PredictionCache(keyValueDao, clockAt(computedAt)).read(43))
    }

    @Test
    fun `a fresh estimate read back is the same estimate`() = runTest {
        val cache = PredictionCache(keyValueDao, clockAt(computedAt))
        cache.save(42, predictions())

        val cached = cache.read(42)!!

        assertEquals(predictions().predictions, cached.predictions)
        assertFalse(predictions().isFromCache)
    }

    private fun predictions() = CanyonDebitPredictions(
        target = WeatherTarget(45.0, 6.0, WeatherLocationSource.WATERSHED_CENTER),
        timezone = "Europe/Paris",
        fetchedAt = computedAt,
        lookupSource = RuntimeLookupSource.CANYON,
        usedWeatherCache = false,
        policy = DebitPredictionPolicy.PRUDENT,
        ordinalCutpoints = listOf(0.5, 1.5, 2.5, 3.5, 4.5),
        predictions = (0..2).map { horizon ->
            DailyDebitPrediction(
                date = LocalDate.of(2026, 7, 14).plusDays(horizon.toLong()),
                horizonDays = horizon,
                level = PredictedDebitLevel.HIGH,
                probabilities = mapOf(PredictedDebitLevel.HIGH to 0.31, PredictedDebitLevel.LOW to 0.2),
                highThreshold = 0.26,
                ordinalScore = 3.1,
                ordinalLevel = NiveauDebit.GROS,
                ordinalStandardDeviation = 0.8,
                levelProbabilities = mapOf(NiveauDebit.CORRECT to 0.35, NiveauDebit.GROS to 0.4, NiveauDebit.TRES_GROS to 0.25),
            )
        },
    )

    private fun clockAt(instant: Instant): Clock = Clock.fixed(instant, ZoneId.of("Europe/Paris"))
}
