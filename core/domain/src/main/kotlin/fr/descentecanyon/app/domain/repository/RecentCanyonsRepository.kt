package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CanyonSummary
import kotlinx.coroutines.flow.Flow

/** Canyons whose detail the user opened, kept on the device for quick (offline) access. */
interface RecentCanyonsRepository {
    /** Most recently viewed first. */
    fun observeRecentCanyons(limit: Int): Flow<List<CanyonSummary>>

    suspend fun recordView(canyonId: Int)

    suspend fun clear()
}
