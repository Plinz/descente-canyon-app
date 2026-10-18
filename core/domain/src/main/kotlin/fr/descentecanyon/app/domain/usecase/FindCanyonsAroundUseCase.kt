package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.CanyonAround
import fr.descentecanyon.app.domain.model.distanceToCanyonKm
import fr.descentecanyon.app.domain.model.haversineKm
import fr.descentecanyon.app.domain.model.toSummary
import fr.descentecanyon.app.domain.repository.CanyonRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Canyons the user may be in, from the offline catalog: the closest ones by their catalog position,
 * ranked by the distance to their route. Lets the emergency screen name the canyon by itself.
 */
class FindCanyonsAroundUseCase @Inject constructor(private val canyonRepository: CanyonRepository) {

    /** [maxDistanceKm] grows with the uncertainty of a vague position, which may be that far from the user. */
    suspend operator fun invoke(latitude: Double, longitude: Double, maxDistanceKm: Double = MAX_DISTANCE_KM): List<CanyonAround> {
        val candidates = canyonRepository.observeSearchCatalog().first()
            .mapNotNull { item ->
                val itemLatitude = item.representativeLat ?: return@mapNotNull null
                val itemLongitude = item.representativeLng ?: return@mapNotNull null
                val distance = haversineKm(latitude, longitude, itemLatitude, itemLongitude)
                if (distance <= CANDIDATE_RADIUS_KM + maxDistanceKm - MAX_DISTANCE_KM) item to distance else null
            }
            .sortedBy { (_, distance) -> distance }
            .take(MAX_CANDIDATES)
        return candidates
            .mapNotNull { (item, catalogDistance) ->
                val points = canyonRepository.getCanyonPreview(item.id).getOrNull()?.geoPoints.orEmpty()
                val distance = minOf(catalogDistance, distanceToCanyonKm(latitude, longitude, points) ?: catalogDistance)
                if (distance <= maxDistanceKm) CanyonAround(item.toSummary(), distance) else null
            }
            .sortedWith(compareBy<CanyonAround> { it.distanceKm }.thenBy { it.summary.nom })
    }

    companion object {
        /** A long canyon can have its catalog position this far from the user. */
        const val CANDIDATE_RADIUS_KM = 15.0
        const val MAX_CANDIDATES = 12

        /** Farther than this from every canyon route, the user is not taken to be in one. */
        const val MAX_DISTANCE_KM = 3.0
    }
}
