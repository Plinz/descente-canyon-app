package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.FavoriteFolder
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing user's favorite canyons and folders.
 */
interface FavoritesRepository {

    fun getFavorites(): Flow<List<CanyonSummary>>

    suspend fun addFavorite(canyonId: Int)

    suspend fun removeFavorite(canyonId: Int)

    fun isFavorite(canyonId: Int): Flow<Boolean>

    fun getFolders(): Flow<List<FavoriteFolder>>

    suspend fun createFolder(name: String): Long

    suspend fun deleteFolder(folderId: Int)

    suspend fun addCanyonToFolder(canyonId: Int, folderId: Int)

    suspend fun removeCanyonFromFolder(canyonId: Int, folderId: Int)

    fun getFolderIdsForCanyon(canyonId: Int): Flow<List<Int>>

    fun getCanyonIdsForFolder(folderId: Int): Flow<List<Int>>

    fun getAllCanyonFolderMap(): Flow<Map<Int, Set<Int>>>
}
