package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.catalog.dao.CanyonDao
import fr.descentecanyon.app.data.local.user.dao.FavoriteDao
import fr.descentecanyon.app.data.local.user.entity.FavoriteEntity
import fr.descentecanyon.app.data.mapper.toSummary
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Favorites are user data (user database) pointing to canyons of the catalog database. */
@Singleton
class FavoritesRepositoryImpl @Inject constructor(private val favoriteDao: FavoriteDao, private val canyonDao: CanyonDao) :
    FavoritesRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getFavorites(): Flow<List<CanyonSummary>> = favoriteDao.observeIds().flatMapLatest { ids ->
        if (ids.isEmpty()) {
            flowOf(emptyList())
        } else {
            // Canyons removed from a newer catalog are skipped; most recently added first.
            canyonDao.observeByIds(ids).map { entities ->
                val byId = entities.associateBy { it.id }
                ids.mapNotNull { id -> byId[id]?.toSummary() }
            }
        }
    }

    override fun observeFavoriteIds(): Flow<Set<Int>> = favoriteDao.observeIds().map { it.toSet() }

    override suspend fun addFavorite(canyonId: Int) {
        favoriteDao.insert(FavoriteEntity(canyonId = canyonId))
    }

    override suspend fun removeFavorite(canyonId: Int) {
        favoriteDao.delete(canyonId)
    }

    override fun isFavorite(canyonId: Int): Flow<Boolean> = favoriteDao.observeIsFavorite(canyonId)
}
