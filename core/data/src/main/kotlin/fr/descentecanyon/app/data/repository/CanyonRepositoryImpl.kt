package fr.descentecanyon.app.data.repository

import androidx.room.withTransaction
import fr.descentecanyon.app.data.local.catalog.CatalogDatabase
import fr.descentecanyon.app.data.local.catalog.dao.CanyonDao
import fr.descentecanyon.app.data.local.catalog.dao.SearchIndexDao
import fr.descentecanyon.app.data.local.catalog.dao.WatershedDao
import fr.descentecanyon.app.data.local.catalog.dao.toDomain
import fr.descentecanyon.app.data.local.catalog.entity.CanyonEntity
import fr.descentecanyon.app.data.mapper.toEntity
import fr.descentecanyon.app.data.mapper.toSearchIndexEntity
import fr.descentecanyon.app.data.mapper.toSearchItem
import fr.descentecanyon.app.data.mapper.toSummary
import fr.descentecanyon.app.data.remote.scraper.CanyonScraper
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import fr.descentecanyon.app.domain.error.failure
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.CanyonWatershed
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

@Singleton
class CanyonRepositoryImpl @Inject constructor(
    private val catalogDatabase: CatalogDatabase,
    private val canyonDao: CanyonDao,
    private val searchIndexDao: SearchIndexDao,
    private val watershedDao: WatershedDao,
    private val localStore: CanyonLocalStore,
    private val scraper: CanyonScraper,
    private val favoritesRepository: FavoritesRepository,
    private val connectivityObserver: ConnectivityObserver,
) : CanyonRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val searchIndexFlow by lazy {
        searchIndexDao.observeAll()
            .map { rows -> rows.map { it.toSearchItem() } }
            .flowOn(Dispatchers.Default)
            .shareIn(
                scope = repositoryScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                replay = 1,
            )
    }

    override fun observeSearchCatalog(): Flow<List<CanyonSearchItem>> = combine(searchIndexFlow, favoritesRepository.observeFavoriteIds()) {
            items,
            favoriteIds,
        ->
        if (favoriteIds.isEmpty()) items else items.map { it.copy(isFavorite = it.id in favoriteIds) }
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    override suspend fun getCanyonDetail(canyonId: Int): Result<CanyonDetail> {
        val localCanyon = canyonDao.getById(canyonId)
            ?: return fetchAndCacheRemoteDetail(canyonId)

        if (localCanyon.isStale() && connectivityObserver.isCurrentlyOnline()) {
            // Stale-while-revalidate: on failure the catalog copy is still shown.
            fetchAndCacheRemoteDetail(canyonId).onSuccess { return Result.success(it) }
        }
        return suspendRunCatching { localStore.loadLocalDetail(canyonId, localCanyon) }
    }

    override suspend fun getCanyonPreview(canyonId: Int): Result<CanyonDetail> {
        val localCanyon = canyonDao.getById(canyonId) ?: return failure(AppError.DataUnavailable)
        return suspendRunCatching { localStore.loadLocalDetail(canyonId, localCanyon) }
    }

    override suspend fun getCanyonSummaries(ids: Collection<Int>): List<CanyonSummary> = if (ids.isEmpty()) {
        emptyList()
    } else {
        canyonDao.getByIds(ids.distinct()).map { it.toSummary() }
    }

    override suspend fun getCatalogSize(): Int = canyonDao.count()

    override fun observeWatershed(canyonId: Int): Flow<CanyonWatershed?> = watershedDao.observeMetadataByCanyonId(canyonId).map {
        it?.toDomain()
    }

    override suspend fun getWatershedGeometry(canyonId: Int): String? = watershedDao.getGeometryByCanyonId(canyonId)

    private suspend fun fetchAndCacheRemoteDetail(canyonId: Int): Result<CanyonDetail> = suspendRunCatching {
        val detail = scraper.scrapeFullCanyonDetail(canyonId).getOrThrow()
        // A page without a canyon name means its layout changed: never overwrite the catalog with it.
        if (detail.nom.isBlank()) appError(AppError.SiteFormatChanged)

        val scraped = detail.toEntity()
        val geoPointEntities = detail.geoPoints.map { it.toEntity(canyonId) }
        catalogDatabase.withTransaction {
            val isNewCanyon = canyonDao.getById(canyonId) == null
            localStore.upsertScrapedCanyon(scraped)
            if (geoPointEntities.isNotEmpty()) {
                localStore.replaceGeoPoints(canyonId, geoPointEntities)
            }
            if (isNewCanyon) {
                // Search fields of catalog canyons are computed at build time; only index new ones here.
                val point = localStore.bestMarkerPointOrNull(geoPointEntities)
                searchIndexDao.upsert(
                    scraped.toSearchItem(representativeLat = point?.latitude, representativeLng = point?.longitude)
                        .toSearchIndexEntity(),
                )
            }
        }
        val stored = canyonDao.getById(canyonId) ?: appError(AppError.StorageFailure)
        localStore.loadLocalDetail(canyonId, stored)
    }

    private fun CanyonEntity.isStale(): Boolean = System.currentTimeMillis() - lastUpdated > STALE_AFTER_MS

    private companion object {
        /** Topos change rarely: refresh a detail at most monthly, which keeps the load on the site low. */
        const val STALE_AFTER_MS = 30L * 24 * 60 * 60 * 1000
    }
}
