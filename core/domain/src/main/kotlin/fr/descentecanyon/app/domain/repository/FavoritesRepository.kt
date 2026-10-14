package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CanyonSummary
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing user's favorite canyons.
 */
interface FavoritesRepository {

    fun getFavorites(): Flow<List<CanyonSummary>>

    fun observeFavoriteIds(): Flow<Set<Int>>

    suspend fun addFavorite(canyonId: Int)

    suspend fun removeFavorite(canyonId: Int)

    fun isFavorite(canyonId: Int): Flow<Boolean>
}
