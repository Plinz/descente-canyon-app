package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CanyonWatershed
import fr.descentecanyon.app.domain.repository.CanyonRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@Singleton
class FakeCanyonRepository @Inject constructor() : CanyonRepository {
    override fun observeSearchCatalog(): Flow<List<CanyonSearchItem>> = E2eFixtureState.favoriteIds.map { E2eFixtureState.catalogItems() }

    override suspend fun getCanyonDetail(canyonId: Int): Result<CanyonDetail> = E2eFixtureState.canyonDetails.value[canyonId]
        ?.let(Result.Companion::success)
        ?: Result.failure(IllegalArgumentException("Unknown canyon $canyonId"))

    override suspend fun getCanyonPreview(canyonId: Int): Result<CanyonDetail> = getCanyonDetail(canyonId)

    override fun observeWatershed(canyonId: Int): Flow<CanyonWatershed?> = flowOf(null)

    override suspend fun getWatershedGeometry(canyonId: Int): String? = null
}
