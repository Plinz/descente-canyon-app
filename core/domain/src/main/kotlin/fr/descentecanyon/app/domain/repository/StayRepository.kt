package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.Stay
import kotlinx.coroutines.flow.Flow

/** The stays the user prepares outings for, kept on the device. */
interface StayRepository {
    /** Stays soonest first. */
    fun observeAll(): Flow<List<Stay>>

    suspend fun get(id: Long): Stay?

    /** Adds a stay ([Stay.id] 0) or replaces it; returns its id. */
    suspend fun save(stay: Stay): Long

    /** Forgets the stay and the canyons planned for its days; the outing under way stays planned without it. */
    suspend fun delete(id: Long)
}
