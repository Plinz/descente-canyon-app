package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.DebitDao
import fr.descentecanyon.app.data.mapper.toDomain
import fr.descentecanyon.app.data.mapper.toEntity
import fr.descentecanyon.app.data.remote.scraper.CanyonScraper
import fr.descentecanyon.app.domain.model.CachedItems
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.repository.DebitRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@Singleton
class DebitRepositoryImpl @Inject constructor(
    private val debitDao: DebitDao,
    private val scraper: CanyonScraper,
    private val snapshotStore: HomeFeedSnapshotStore,
) : DebitRepository {

    override fun getDebitsForCanyon(canyonId: Int): Flow<Result<List<Debit>>> = debitDao.getByCanyonId(canyonId).map { entities ->
        Result.success(entities.map { it.toDomain() })
    }

    override fun getLatestDebits(limit: Int): Flow<Result<List<Debit>>> = flow {
        emit(refreshLatestDebits(limit).map { it.items })
    }

    override suspend fun getCachedLatestDebits(limit: Int): CachedItems<Debit> = snapshotStore.readLatestDebits(limit)

    override fun observeLatestKnownDebits(canyonIds: Set<Int>): Flow<Map<Int, Debit>> {
        if (canyonIds.isEmpty()) return flowOf(emptyMap())
        return combine(
            debitDao.observeForCanyons(canyonIds.toList()),
            snapshotStore.observeLatestDebits(),
        ) { cached, feed ->
            val known = cached.map { it.toDomain() } + feed.items.filter { it.canyonId in canyonIds }
            known.groupBy { it.canyonId }.mapValues { (_, debits) -> debits.maxBy { it.date } }
        }
    }

    override suspend fun refreshLatestDebits(limit: Int): Result<CachedItems<Debit>> = scraper.scrapeLatestDebits().map { scrapedDebits ->
        val allDebits = scrapedDebits.map { it.toDomain() }
        val debits = allDebits.take(limit)
        val syncedAtEpochMs = System.currentTimeMillis()
        snapshotStore.writeLatestDebits(allDebits, syncedAtEpochMs)
        CachedItems(items = debits, syncedAtEpochMs = syncedAtEpochMs)
    }

    override suspend fun refreshDebits(canyonId: Int): Result<List<Debit>> = scraper.scrapeCanyonDebits(
        canyonId = canyonId,
        timeoutMs = REFRESH_TIMEOUT_MS,
    ).map { scrapedDebits ->
        val entities = scrapedDebits.map { it.toEntity() }

        debitDao.replaceForCanyon(canyonId, entities)

        entities.map { it.toDomain() }
    }

    private companion object {
        const val REFRESH_TIMEOUT_MS = 15_000L
    }
}
