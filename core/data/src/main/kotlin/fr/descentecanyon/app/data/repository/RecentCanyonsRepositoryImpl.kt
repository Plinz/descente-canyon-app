package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.catalog.dao.CanyonDao
import fr.descentecanyon.app.data.local.user.dao.RecentCanyonDao
import fr.descentecanyon.app.data.local.user.entity.RecentCanyonEntity
import fr.descentecanyon.app.data.mapper.toSummary
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.repository.RecentCanyonsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Recently viewed canyons are user data (user database) pointing to canyons of the catalog. */
@Singleton
class RecentCanyonsRepositoryImpl @Inject constructor(private val recentCanyonDao: RecentCanyonDao, private val canyonDao: CanyonDao) :
    RecentCanyonsRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeRecentCanyons(limit: Int): Flow<List<CanyonSummary>> =
        recentCanyonDao.observeRecentIds(limit).flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyList())
            } else {
                // Canyons removed from a newer catalog are skipped.
                canyonDao.observeByIds(ids).map { entities ->
                    val byId = entities.associateBy { it.id }
                    ids.mapNotNull { id -> byId[id]?.toSummary() }
                }
            }
        }

    override suspend fun recordView(canyonId: Int) {
        recentCanyonDao.recordView(
            RecentCanyonEntity(canyonId = canyonId, viewedAt = System.currentTimeMillis()),
            keep = MAX_RECENT_CANYONS,
        )
    }

    override suspend fun clear() {
        recentCanyonDao.clear()
    }

    private companion object {
        const val MAX_RECENT_CANYONS = 30
    }
}
