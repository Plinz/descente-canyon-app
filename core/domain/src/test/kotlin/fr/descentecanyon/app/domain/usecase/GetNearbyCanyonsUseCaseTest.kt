package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GetNearbyCanyonsUseCaseTest {

    private val canyonRepository = mockk<CanyonRepository>()
    private val debitRepository = mockk<DebitRepository>()
    private val useCase = GetNearbyCanyonsUseCase(canyonRepository, debitRepository)

    @Test
    fun `nearby canyons get their last known report`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(
            listOf(item(1, 45.0, 6.0), item(2, 45.01, 6.0), item(3, 45.02, 6.0)),
        )
        every { debitRepository.observeLatestKnownDebits(setOf(2, 3)) } returns flowOf(
            mapOf(3 to Debit(canyonId = 3, date = LocalDate.of(2026, 7, 1), niveau = NiveauDebit.GROS)),
        )

        val nearby = useCase(1).first()

        assertEquals(listOf(2, 3), nearby.map { it.summary.id })
        assertNull(nearby[0].summary.dernierDebit)
        assertEquals(NiveauDebit.GROS, nearby[1].summary.dernierDebit)
        assertEquals(LocalDate.of(2026, 7, 1), nearby[1].summary.dernierDebitDate)
    }

    @Test
    fun `an isolated canyon does not query the reports`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(listOf(item(1, 45.0, 6.0), item(2, 47.0, 6.0)))

        assertTrue(useCase(1).first().isEmpty())
        verify(exactly = 0) { debitRepository.observeLatestKnownDebits(any()) }
    }

    private fun item(id: Int, latitude: Double, longitude: Double) = CanyonSearchItem(
        id = id,
        nom = "Canyon $id",
        nomComplet = "Canyon $id",
        pays = "France",
        cotation = "v3a3II",
        cotationRating = CotationRating.parse("v3a3II"),
        representativeLat = latitude,
        representativeLng = longitude,
        url = "/canyoning/canyon/$id.html",
        searchableText = "canyon $id",
        normalizedNom = "canyon $id",
        normalizedNomComplet = "canyon $id",
    )
}
