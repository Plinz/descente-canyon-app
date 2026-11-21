package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.DailyDebitPrediction
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.DebitPredictionPolicy
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.PredictedDebitLevel
import fr.descentecanyon.app.domain.model.RuntimeLookupSource
import fr.descentecanyon.app.domain.model.WeatherLocationSource
import fr.descentecanyon.app.domain.model.WeatherTarget
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitPredictionRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.testutil.InMemoryKeyValueDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutingEstimateMonitorTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val clock = Clock.fixed(Instant.parse("2026-07-14T17:00:00Z"), zone)
    private val tomorrow = LocalDate.of(2026, 7, 15)
    private val outing = PlannedOuting(
        canyonId = 42,
        canyonName = "Riolan",
        start = tomorrow.atTime(9, 0).atZone(zone).toInstant(),
        expectedBack = tomorrow.atTime(15, 0).atZone(zone).toInstant(),
        alertAt = tomorrow.atTime(19, 0).atZone(zone).toInstant(),
        id = 5,
    )
    private val outings = MutableStateFlow(listOf(outing))
    private val outingRepository = mockk<OutingRepository> { every { observeAll() } returns outings }
    private val canyonRepository = mockk<CanyonRepository> { coEvery { getCanyonPreview(42) } returns Result.success(detail()) }
    private val freshReports = listOf(Debit(id = 1, canyonId = 42, date = LocalDate.of(2026, 7, 14), niveau = NiveauDebit.GROS))
    private val debitRepository = mockk<DebitRepository> { coEvery { refreshDebits(42, any()) } returns Result.success(freshReports) }
    private var level = PredictedDebitLevel.HIGH
    private val predictionRepository = mockk<DebitPredictionRepository> {
        coEvery { getPredictions(any()) } answers { Result.success(predictions(level)) }
    }
    private val online = mockk<ConnectivityObserver> { every { isCurrentlyOnline() } returns true }
    private val monitor =
        OutingEstimateMonitor(
            outingRepository,
            canyonRepository,
            debitRepository,
            predictionRepository,
            InMemoryKeyValueDao(),
            online,
            clock,
        )

    @Test
    fun `a high-flow risk on the day of an outing is told once, with the reports read again`() = runTest {
        val alerts = monitor.refresh()

        assertEquals(1, alerts.size)
        assertEquals(1, alerts.single().horizonDays)
        assertEquals("Riolan", alerts.single().canyonName)
        coVerify { predictionRepository.getPredictions(match { it.debits == freshReports }) }

        monitor.markAlerted(alerts.single())
        assertTrue(monitor.refresh().isEmpty())
    }

    @Test
    fun `a risk that falls back is told again when it returns`() = runTest {
        monitor.markAlerted(monitor.refresh().single())
        level = PredictedDebitLevel.MEDIUM
        assertTrue(monitor.refresh().isEmpty())

        level = PredictedDebitLevel.HIGH
        assertEquals(1, monitor.refresh().size)
    }

    @Test
    fun `outings beyond the days estimated or already started are left out`() = runTest {
        outings.value = listOf(
            outing.copy(id = 6, start = tomorrow.plusDays(5).atTime(9, 0).atZone(zone).toInstant()),
            outing.copy(id = 7, start = clock.instant().minusSeconds(3600)),
        )

        assertTrue(monitor.refresh().isEmpty())
        coVerify(exactly = 0) { predictionRepository.getPredictions(any()) }
    }

    private fun predictions(level: PredictedDebitLevel) = CanyonDebitPredictions(
        target = WeatherTarget(43.0, 6.0, WeatherLocationSource.UNKNOWN),
        timezone = "Europe/Paris",
        fetchedAt = clock.instant(),
        lookupSource = RuntimeLookupSource.CANYON,
        usedWeatherCache = false,
        policy = DebitPredictionPolicy.BALANCED,
        predictions = (0..2).map { horizon ->
            DailyDebitPrediction(
                date = LocalDate.of(2026, 7, 14).plusDays(horizon.toLong()),
                horizonDays = horizon,
                level = level,
                probabilities = mapOf(PredictedDebitLevel.HIGH to 0.45),
                highThreshold = 0.3,
                ordinalLevel = NiveauDebit.GROS,
            )
        },
    )

    private fun detail() = CanyonDetail(
        canyon = Canyon(
            id = 42,
            nom = "Riolan",
            nomComplet = "Canyon du Riolan",
            pays = "France",
            commune = "Sigale",
            cotation = "v4a4III",
            url = "/c/42",
        ),
        geoPoints = listOf(GeoPoint(id = 1, canyonId = 42, type = GeoPointType.PARKING_AMONT, latitude = 43.9, longitude = 6.9)),
    )
}
