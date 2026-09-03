package fr.descentecanyon.app.data.repository

import androidx.room.withTransaction
import fr.descentecanyon.app.data.local.dao.CanyonDao
import fr.descentecanyon.app.data.local.dao.FavoriteFolderDao
import fr.descentecanyon.app.data.local.dao.SearchIndexDao
import fr.descentecanyon.app.data.local.database.DescenteCanyonDatabase
import fr.descentecanyon.app.data.local.entity.CanyonFavoriteFolderCrossRef
import fr.descentecanyon.app.data.local.entity.FavoriteFolderEntity
import fr.descentecanyon.app.data.mapper.toSummary
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.FavoriteFolder
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoritesRepositoryImpl @Inject constructor(
    private val database: DescenteCanyonDatabase,
    private val canyonDao: CanyonDao,
    private val favoriteFolderDao: FavoriteFolderDao,
    private val searchIndexDao: SearchIndexDao,
) : FavoritesRepository {

    override fun getFavorites(): Flow<List<CanyonSummary>> {
        return canyonDao.getFavorites().map { entities ->
            entities.map { it.toSummary() }
        }
    }

    override suspend fun addFavorite(canyonId: Int) {
        database.withTransaction {
            val now = System.currentTimeMillis()
            canyonDao.setFavorite(canyonId, true, now)
            searchIndexDao.setFavorite(canyonId, true)
        }
    }

    override suspend fun removeFavorite(canyonId: Int) {
        database.withTransaction {
            canyonDao.setFavorite(canyonId, false, null)
            searchIndexDao.setFavorite(canyonId, false)
            favoriteFolderDao.removeAllFoldersForCanyon(canyonId)
        }
    }

    override fun isFavorite(canyonId: Int): Flow<Boolean> {
        return canyonDao.isFavorite(canyonId).map { it ?: false }
    }

    override fun getFolders(): Flow<List<FavoriteFolder>> {
        return combine(
            favoriteFolderDao.getAllFolders(),
            favoriteFolderDao.getAllCrossRefs()
        ) { folders, crossRefs ->
            val countMap = crossRefs.groupingBy { it.folderId }.eachCount()
            folders.map { entity ->
                FavoriteFolder(
                    id = entity.id,
                    name = entity.name,
                    canyonCount = countMap[entity.id] ?: 0,
                    createdAt = entity.createdAt,
                )
            }
        }
    }

    override suspend fun createFolder(name: String): Long {
        return favoriteFolderDao.insertFolder(
            FavoriteFolderEntity(
                name = name.trim(),
                createdAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun deleteFolder(folderId: Int) {
        database.withTransaction {
            favoriteFolderDao.deleteFolderCrossRefs(folderId)
            favoriteFolderDao.deleteFolder(folderId)
        }
    }

    override suspend fun addCanyonToFolder(canyonId: Int, folderId: Int) {
        favoriteFolderDao.addCanyonToFolder(
            CanyonFavoriteFolderCrossRef(canyonId = canyonId, folderId = folderId)
        )
    }

    override suspend fun removeCanyonFromFolder(canyonId: Int, folderId: Int) {
        favoriteFolderDao.removeCanyonFromFolder(canyonId = canyonId, folderId = folderId)
    }

    override fun getFolderIdsForCanyon(canyonId: Int): Flow<List<Int>> {
        return favoriteFolderDao.getFolderIdsForCanyon(canyonId)
    }

    override fun getCanyonIdsForFolder(folderId: Int): Flow<List<Int>> {
        return favoriteFolderDao.getCanyonIdsForFolder(folderId)
    }

    override fun getAllCanyonFolderMap(): Flow<Map<Int, Set<Int>>> {
        return favoriteFolderDao.getAllCrossRefs().map { crossRefs ->
            val map = mutableMapOf<Int, MutableSet<Int>>()
            crossRefs.forEach { ref ->
                map.getOrPut(ref.canyonId) { mutableSetOf() }.add(ref.folderId)
            }
            map.mapValues { it.value.toSet() }
        }
    }
}
