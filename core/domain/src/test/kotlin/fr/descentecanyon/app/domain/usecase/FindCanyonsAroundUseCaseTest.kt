package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.repository.CanyonRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FindCanyonsAroundUseCaseTest {

    private val canyonRepository = mockk<CanyonRepository>()
    private val useCase = FindCanyonsAroundUseCase(canyonRepository)

    @Test
    fun `the canyon whose route passes by the position comes first`() = runTest {
        // Canyon 1 is listed at its parking, 4 km away, but its route passes at the position.
        every { canyonRepository.observeSearchCatalog() } returns flowOf(
            listOf(item(1, 45.0, 6.0), item(2, 45.04, 6.08), item(3, 45.5, 6.0)),
        )
        detail(
            1,
            GeoPoint(canyonId = 1, type = GeoPointType.PARKING_AVAL, latitude = 45.0, longitude = 6.0),
            GeoPoint(canyonId = 1, type = GeoPointType.ENTREE, latitude = 45.04, longitude = 6.03),
            GeoPoint(canyonId = 1, type = GeoPointType.SORTIE, latitude = 45.04, longitude = 6.06),
        )
        detail(2, GeoPoint(canyonId = 2, type = GeoPointType.ENTREE, latitude = 45.04, longitude = 6.08))

        val around = useCase(latitude = 45.04, longitude = 6.045)

        assertEquals(listOf(1, 2), around.map { it.summary.id })
        assertEquals(0.0, around[0].distanceKm, 0.01)
        // 2.75 km east, a neighbour canyon offered as an alternative; canyon 3 is out of reach.
        assertEquals(2.75, around[1].distanceKm, 0.05)
    }

    @Test
    fun `no canyon within reach gives nothing`() = runTest {
        every { canyonRepository.observeSearchCatalog() } returns flowOf(listOf(item(1, 46.0, 6.0)))

        assertTrue(useCase(latitude = 45.0, longitude = 6.0).isEmpty())
    }

    private fun detail(id: Int, vararg points: GeoPoint) {
        coEvery { canyonRepository.getCanyonPreview(id) } returns Result.success(
            CanyonDetail(
                canyon = Canyon(
                    id = id,
                    nom = "Canyon $id",
                    nomComplet = "Canyon $id",
                    pays = "France",
                    commune = "Town",
                    cotation = "v3a3II",
                    url = "",
                ),
                geoPoints = points.toList(),
            ),
        )
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
