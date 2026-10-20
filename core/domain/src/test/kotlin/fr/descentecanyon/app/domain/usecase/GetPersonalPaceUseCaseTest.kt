package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.PersonalPace
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GetPersonalPaceUseCaseTest {

    private val canyonRepository = mockk<CanyonRepository> {
        coEvery { getCanyonPreview(1) } returns Result.success(detail(1, "30min", "2h", "30min"))
        coEvery { getCanyonPreview(2) } returns Result.success(detail(2, "1h", "4h", "1h"))
        coEvery { getCanyonPreview(3) } returns Result.success(detail(3, "??", "2h", "10min"))
    }

    @Test
    fun `timed outings of canyons with known times give the pace`() = runTest {
        val entries = listOf(
            entry(1, 200),
            entry(1, 180),
            entry(2, 420),
            // Unknown approach on the sheet: not comparable.
            entry(3, 150),
            entry(2, null),
        )

        assertEquals(PersonalPace(1.11, 3), useCase(entries)())
    }

    @Test
    fun `fewer than three timed outings give no pace`() = runTest {
        assertNull(useCase(listOf(entry(1, 200), entry(2, 400), entry(2, null)))())
    }

    private fun useCase(entries: List<LogbookEntry>) = GetPersonalPaceUseCase(
        logbookRepository = mockk<LogbookRepository> { every { observeAll() } returns flowOf(entries) },
        canyonRepository = canyonRepository,
    )

    private var day = LocalDate.of(2026, 7, 1)

    private fun entry(canyonId: Int, minutes: Int?) = LogbookEntry(
        canyonId = canyonId,
        canyonName = "C$canyonId",
        date = day.also { day = day.plusDays(1) },
        durationMinutes = minutes,
    )

    private fun detail(id: Int, approach: String, descent: String, back: String) = CanyonDetail(
        canyon = Canyon(
            id = id, nom = "C$id", nomComplet = "C$id", pays = "France", commune = "", cotation = "v3", url = "",
            tempsApproche = approach, tempsDescente = descent, tempsRetour = back,
        ),
    )
}
