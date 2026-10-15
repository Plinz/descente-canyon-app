package fr.descentecanyon.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyCanyonTest {

    private val catalog = listOf(
        item(1, "Origine", 45.0, 6.0),
        item(2, "Voisin proche", 45.01, 6.0),
        item(3, "Voisin moyen", 45.1, 6.0),
        item(4, "Trop loin", 46.0, 6.0),
        item(5, "Sans position", null, null),
        item(6, "Ex aequo A", 45.1, 6.0),
    )

    @Test
    fun `nearest canyons are sorted by distance, the canyon itself and far ones excluded`() {
        val nearby = nearestCanyons(canyonId = 1, catalog = catalog, maxDistanceKm = 30.0, limit = 10)

        assertEquals(listOf(2, 6, 3), nearby.map { it.summary.id })
        assertEquals(1.11, nearby.first().distanceKm, 0.01)
        assertEquals(11.1, nearby.last().distanceKm, 0.1)
    }

    @Test
    fun `the limit keeps the closest ones`() {
        assertEquals(listOf(2), nearestCanyons(1, catalog, 30.0, limit = 1).map { it.summary.id })
    }

    @Test
    fun `a canyon without position or missing from the catalog has no neighbours`() {
        assertTrue(nearestCanyons(5, catalog, 30.0, 10).isEmpty())
        assertTrue(nearestCanyons(99, catalog, 30.0, 10).isEmpty())
    }

    @Test
    fun `haversine distance matches a known value`() {
        // Paris Notre-Dame to Lyon Fourvière: about 392 km.
        assertEquals(392.0, haversineKm(48.8530, 2.3499, 45.7623, 4.8228), 2.0)
    }

    private fun item(id: Int, name: String, latitude: Double?, longitude: Double?) = CanyonSearchItem(
        id = id,
        nom = name,
        nomComplet = name,
        pays = "France",
        cotation = "v3a3II",
        cotationRating = CotationRating.parse("v3a3II"),
        representativeLat = latitude,
        representativeLng = longitude,
        url = "/canyoning/canyon/$id.html",
        searchableText = name.lowercase(),
        normalizedNom = name.lowercase(),
        normalizedNomComplet = name.lowercase(),
    )
}
