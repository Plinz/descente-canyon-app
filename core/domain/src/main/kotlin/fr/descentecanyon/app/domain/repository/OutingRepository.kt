package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.PlannedOuting
import kotlinx.coroutines.flow.Flow

/** The outing the user announced (one at a time), kept on the device only. */
interface OutingRepository {
    /** The current outing; null when none is planned or the last one is long finished. */
    fun observeCurrent(): Flow<PlannedOuting?>

    suspend fun save(outing: PlannedOuting)

    suspend fun clear()
}
