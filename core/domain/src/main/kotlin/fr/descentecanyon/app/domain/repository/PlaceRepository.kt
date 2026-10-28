package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.Place
import kotlinx.coroutines.flow.Flow

/** The places outings start from (home, campsites), kept on the device. */
interface PlaceRepository {
    /** Places used last first, then by name. */
    fun observeAll(): Flow<List<Place>>

    suspend fun get(id: Long): Place?

    /** Adds a place ([Place.id] 0) or replaces it; returns its id. */
    suspend fun save(place: Place): Long

    /** Forgets the place, its drive times and its stays (with the canyons planned for their days). */
    suspend fun delete(id: Long)

    /** An outing was planned from it: it comes first in the lists. */
    suspend fun markUsed(id: Long)
}
