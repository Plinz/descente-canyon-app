package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.PlannedOuting
import kotlinx.coroutines.flow.Flow

/** The outings the user planned, kept on the device only. */
interface OutingRepository {
    /** Planned outings not long finished, soonest first. */
    fun observeAll(): Flow<List<PlannedOuting>>

    suspend fun get(id: Long): PlannedOuting?

    /** Adds an outing ([PlannedOuting.id] 0) or replaces it; returns its id. */
    suspend fun save(outing: PlannedOuting): Long

    suspend fun delete(id: Long)

    /** Forgets every planned outing. */
    suspend fun clear()
}
