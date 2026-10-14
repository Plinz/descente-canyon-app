package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.model.CachedItems
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.repository.DebitRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@Singleton
class FakeDebitRepository @Inject constructor() : DebitRepository {
    override fun getDebitsForCanyon(canyonId: Int): Flow<Result<List<Debit>>> = E2eFixtureState.canyonDetails.map { details ->
        Result.success(details[canyonId]?.debits.orEmpty())
    }

    override fun getLatestDebits(limit: Int): Flow<Result<List<Debit>>> = E2eFixtureState.latestDebits.map {
        Result.success(it.take(limit))
    }

    override suspend fun getCachedLatestDebits(limit: Int): CachedItems<Debit> =
        CachedItems(items = E2eFixtureState.latestDebits.value.take(limit))

    override suspend fun refreshLatestDebits(limit: Int): Result<CachedItems<Debit>> = Result.success(getCachedLatestDebits(limit))

    override suspend fun refreshDebits(canyonId: Int): Result<List<Debit>> =
        Result.success(E2eFixtureState.canyonDetails.value[canyonId]?.debits.orEmpty())
}
