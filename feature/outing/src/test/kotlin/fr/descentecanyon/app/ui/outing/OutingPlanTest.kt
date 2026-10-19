package fr.descentecanyon.app.ui.outing

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.DaylightCheck
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.LogbookSource
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.repository.CanyonNotesRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitPredictionRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.WeatherRepository
import fr.descentecanyon.app.domain.usecase.EndOutingUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonDebitPredictionsUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonPreviewUseCase
import fr.descentecanyon.app.domain.usecase.GetCanyonWeatherUseCase
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutingPlanTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val zone = ZoneId.of("Europe/Paris")
    private val clock = Clock.fixed(LocalDateTime.of(2026, 7, 14, 8, 5).atZone(zone).toInstant(), zone)
    private val saved = MutableStateFlow<PlannedOuting?>(null)
    private val logged = mutableListOf<LogbookEntry>()
    private val logbookRepository = mockk<LogbookRepository> {
        coEvery { record(any()) } answers {
            logged += firstArg<LogbookEntry>()
            true
        }
    }
    private val memo = MutableStateFlow<String?>(null)
    private val canyonNotesRepository = mockk<CanyonNotesRepository> { every { observeNote(42) } returns memo }
    private val outingRepository = object : OutingRepository {
        override fun observeCurrent() = saved
        override suspend fun save(outing: PlannedOuting) {
            saved.value = outing
        }
        override suspend fun clear() {
            saved.value = null
        }
    }

    @Test
    fun `default start is the next quarter hour in the morning, else tomorrow at nine`() {
        assertEquals(
            OutingChoices(LocalDate.of(2026, 7, 14), LocalTime.of(8, 15)),
            OutingPlanViewModel.defaultChoices(LocalDateTime.of(2026, 7, 14, 8, 5)),
        )
        assertEquals(
            OutingChoices(LocalDate.of(2026, 7, 15), LocalTime.of(9, 0)),
            OutingPlanViewModel.defaultChoices(LocalDateTime.of(2026, 7, 14, 15, 40)),
        )
    }

    @Test
    fun `the plan uses the sheet durations, an offline sunset and saves the outing`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(LocalDateTime.of(2026, 7, 14, 11, 45), state.expectedBack) // 8:15 + 30 min + 2h + 1h
        assertTrue(state.daylight is DaylightCheck.Ok)
        assertEquals(listOf("112", "15", "18", "17"), state.emergencyNumbers.map { it.number })

        viewModel.selectAlertMargin(3)
        viewModel.saveOuting()
        advanceUntilIdle()

        val outing = saved.value!!
        assertEquals(42, outing.canyonId)
        assertEquals(Duration.ofHours(3), Duration.between(outing.expectedBack, outing.alertAt))
        assertEquals(outing, viewModel.uiState.value.savedOuting)

        viewModel.markBack()
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.savedOuting)
        assertEquals(listOf(LocalDate.of(2026, 7, 14)), logged.map { it.date })
        assertEquals(LogbookSource.OUTING, logged.single().source)
    }

    @Test
    fun `the memo of the canyon is read again while preparing`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.memo)

        memo.value = "Parking après le pont, 2x40 m"
        advanceUntilIdle()
        assertEquals("Parking après le pont, 2x40 m", viewModel.uiState.value.memo)
    }

    @Test
    fun `the message for a relative gives the place, times, number and parkings`() {
        val texts = OutingMessageTexts(
            header = { n, c -> "H $n $c" },
            place = { "P $it" },
            whenWithReturn = { d, s, b -> "W $d $s $b" },
            whenWithoutReturn = { d, s -> "W $d $s" },
            alert = { t, n -> "A $t $n" },
            downstreamParking = { "D $it" },
            upstreamParking = { "U $it" },
            sheet = { "S $it" },
        )
        val message = buildOutingMessage(
            texts,
            OutingMessageData(
                canyonName = "Riolan", cotation = "v4a4III", place = "Sigale", day = "Mardi", start = "09:00", back = "15:00",
                alertTime = "17:00", emergencyNumber = "112",
                downstreamParking = GeoPoint(canyonId = 42, type = GeoPointType.PARKING_AVAL, latitude = 43.8, longitude = 6.9),
                upstreamParking = null, sheetUrl = "https://example.test/42",
            ),
        )
        assertEquals(
            listOf(
                "H Riolan v4a4III",
                "P Sigale",
                "W Mardi 09:00 15:00",
                "A 17:00 112",
                "D 43.80000, 6.90000 (https://www.openstreetmap.org/?mlat=43.80000&mlon=6.90000#map=15/43.80000/6.90000)",
                "S https://example.test/42",
            ),
            message.lines(),
        )
        assertEquals("43° 48.000' N, 6° 54.000' E", toDegreesMinutes(43.8, 6.9))
    }

    private fun viewModel(): OutingPlanViewModel {
        val canyonRepository = mockk<CanyonRepository> {
            coEvery { getCanyonPreview(42) } returns Result.success(
                CanyonDetail(
                    canyon = Canyon(
                        id = 42, nom = "Riolan", nomComplet = "Riolan", pays = "France", commune = "Sigale", cotation = "v4a4III",
                        url = "https://example.test/42", tempsApproche = "30min", tempsDescente = "2h", tempsRetour = "1h",
                    ),
                    geoPoints = listOf(GeoPoint(canyonId = 42, type = GeoPointType.PARKING_AMONT, latitude = 43.95, longitude = 6.9)),
                ),
            )
        }
        val weather = mockk<WeatherRepository> { coEvery { getCachedCanyonWeather(42) } returns null }
        val predictions = mockk<DebitPredictionRepository> { coEvery { getCachedPredictions(42) } returns null }
        val debits = mockk<DebitRepository> { every { observeLatestKnownDebits(setOf(42)) } returns flowOf(emptyMap()) }
        val connectivity = mockk<ConnectivityObserver> { every { isCurrentlyOnline() } returns false }
        return OutingPlanViewModel(
            savedStateHandle = SavedStateHandle(mapOf("canyonId" to 42)),
            getCanyonPreviewUseCase = GetCanyonPreviewUseCase(canyonRepository),
            getCanyonWeatherUseCase = GetCanyonWeatherUseCase(weather),
            getCanyonDebitPredictionsUseCase = GetCanyonDebitPredictionsUseCase(predictions),
            debitRepository = debits,
            outingRepository = outingRepository,
            canyonNotesRepository = canyonNotesRepository,
            endOutingUseCase = EndOutingUseCase(outingRepository, logbookRepository, clock),
            connectivityObserver = connectivity,
            clock = clock,
        )
    }
}
