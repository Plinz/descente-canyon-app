package fr.descentecanyon.app.startup

import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.usecase.SearchCanyonsUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SearchCatalogWarmupCoordinatorTest {

    private val canyonRepository = mockk<CanyonRepository>()
    private val coordinator = SearchCatalogWarmupCoordinator(SearchCanyonsUseCase(canyonRepository))

    @Test
    fun `warmup observes catalog only once`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(listOf(searchItem()))

        coordinator.warmupIfNeeded()
        coordinator.warmupIfNeeded()

        verify(exactly = 1) { canyonRepository.observeSearchCatalog() }
    }

    private fun searchItem(): CanyonSearchItem = CanyonSearchItem(
        id = 42,
        nom = "Riolan",
        nomComplet = "Clue du Riolan",
        pays = "France",
        cotation = "v3a3III",
        cotationRating = CotationRating.parse("v3a3III"),
        url = "https://www.descente-canyon.com/canyoning/canyon/42/riolan.html",
        searchableText = "riolan clue du riolan france",
        normalizedNom = "riolan",
        normalizedNomComplet = "clue du riolan",
    )
}
