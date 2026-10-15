package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CachedItems
import fr.descentecanyon.app.domain.model.Debit
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for water flow observations.
 */
interface DebitRepository {

    /**
     * Get recent debits for a specific canyon.
     */
    fun getDebitsForCanyon(canyonId: Int): Flow<Result<List<Debit>>>

    /**
     * Get the latest debits reported across all canyons.
     */
    fun getLatestDebits(limit: Int = 20): Flow<Result<List<Debit>>>

    suspend fun getCachedLatestDebits(limit: Int = 20): CachedItems<Debit>

    /**
     * Most recent report known on the device for each of these canyons: from their cached
     * observations and from the last site-wide feed of latest reports. Canyons without any are absent.
     */
    fun observeLatestKnownDebits(canyonIds: Set<Int>): Flow<Map<Int, Debit>>

    suspend fun refreshLatestDebits(limit: Int = 20): Result<CachedItems<Debit>>

    /**
     * Refresh debits from remote source.
     */
    suspend fun refreshDebits(canyonId: Int): Result<List<Debit>>
}
