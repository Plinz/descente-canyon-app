package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.model.FoundPlace
import fr.descentecanyon.app.domain.model.FoundPlaceKind
import fr.descentecanyon.app.domain.repository.PlaceSearchRepository
import javax.inject.Inject
import javax.inject.Singleton

/** No search service in the tests: a campsite near the test canyons answers every search. */
@Singleton
class FakePlaceSearchRepository @Inject constructor() : PlaceSearchRepository {
    override suspend fun search(query: String, nearLatitude: Double?, nearLongitude: Double?, language: String?): Result<List<FoundPlace>> =
        Result.success(listOf(CAMPSITE))

    companion object {
        val CAMPSITE = FoundPlace("Camping du Torrent", "06430 Tende, France", 44.0866, 7.5937, FoundPlaceKind.CAMPSITE)
    }
}
