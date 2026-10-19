package fr.descentecanyon.app.ui.account

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.domain.model.CanyonNote
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.repository.CanyonNotesRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogbookViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val entries = MutableStateFlow(
        listOf(
            LogbookEntry(id = 3, canyonId = 26, canyonName = "Furon", date = LocalDate.of(2026, 7, 30), companions = "Paul"),
            LogbookEntry(id = 2, canyonId = 2283, canyonName = "Ga", date = LocalDate.of(2026, 7, 14), note = "Orage évité de peu"),
            LogbookEntry(id = 1, canyonId = 26, canyonName = "Furon", date = LocalDate.of(2025, 8, 2)),
        ),
    )
    private val notes = MutableStateFlow(
        listOf(
            CanyonNote(22937, "Navette longue, prévoir deux voitures", updatedAt = Instant.parse("2026-09-01T10:00:00Z").toEpochMilli()),
            CanyonNote(26, "Parking après le pont", updatedAt = Instant.parse("2024-01-01T10:00:00Z").toEpochMilli()),
        ),
    )
    private val logbookRepository = mockk<LogbookRepository>(relaxUnitFun = true) {
        every { observeAll() } returns entries
        coEvery { knownCompanions() } returns listOf("Paul")
    }
    private val canyonNotesRepository = mockk<CanyonNotesRepository>(relaxUnitFun = true) { every { observeAll() } returns notes }
    private val canyonRepository = mockk<CanyonRepository> {
        coEvery { getCanyonSummaries(any()) } returns listOf(
            CanyonSummary(id = 26, nom = "Furon (partie haute)", pays = "France", cotation = "v3a3II", url = ""),
            CanyonSummary(id = 2283, nom = "Ga", pays = "France", cotation = "v5a4IV", url = ""),
            CanyonSummary(id = 22937, nom = "Vallungo", pays = "Italie", cotation = "v4a4III", url = ""),
        )
    }

    private fun viewModel(tab: Int? = null) = LogbookViewModel(
        savedStateHandle = SavedStateHandle(listOfNotNull(tab?.let { "tab" to it }).toMap()),
        logbookRepository = logbookRepository,
        canyonNotesRepository = canyonNotesRepository,
        canyonRepository = canyonRepository,
        clock = Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `canyons gather outings and memos, the latest activity first`() = runTest {
        val viewModel = viewModel(tab = 1)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(LogbookTab.CANYONS, viewModel.initialTab)
        assertEquals(
            listOf(
                LogbookCanyonRow(22937, "Vallungo", 0, null, "Navette longue, prévoir deux voitures"),
                LogbookCanyonRow(26, "Furon (partie haute)", 2, LocalDate.of(2026, 7, 30), "Parking après le pont"),
                LogbookCanyonRow(2283, "Ga", 1, LocalDate.of(2026, 7, 14), null),
            ),
            state.canyons,
        )
        assertEquals(3, state.stats.outings)
        assertEquals(LogbookTab.OUTINGS, viewModel(tab = null).initialTab)
    }

    @Test
    fun `one search filters outings and canyons by name, story, companions and memo`() = runTest {
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.onQueryChanged("orage")
        advanceUntilIdle()
        assertEquals(listOf(2L), viewModel.uiState.value.entries.map { it.id })

        viewModel.onQueryChanged("PAUL")
        advanceUntilIdle()
        assertEquals(listOf(3L), viewModel.uiState.value.entries.map { it.id })

        viewModel.onQueryChanged("voitures")
        advanceUntilIdle()
        assertEquals(listOf(22937), viewModel.uiState.value.canyons.map { it.canyonId })
        assertEquals(false, viewModel.uiState.value.isEmpty)
    }

    @Test
    fun `edits go to the logbook and the memos`() = runTest {
        val viewModel = viewModel()
        val edited = entries.value.first().copy(note = "Nouveau récit")

        viewModel.saveEntry(edited)
        viewModel.deleteEntry(1)
        viewModel.saveMemo(26, "")
        advanceUntilIdle()

        coVerify { logbookRepository.save(edited) }
        coVerify { logbookRepository.delete(1) }
        coVerify { canyonNotesRepository.saveNote(26, "") }
    }
}
