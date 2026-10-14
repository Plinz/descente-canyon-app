package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class FakeFavoritesRepository @Inject constructor() : FavoritesRepository {
    override fun getFavorites(): Flow<List<CanyonSummary>> = E2eFixtureState.favoriteIds.map { E2eFixtureState.favorites() }

    override fun observeFavoriteIds(): Flow<Set<Int>> = E2eFixtureState.favoriteIds

    override suspend fun addFavorite(canyonId: Int) {
        E2eFixtureState.favoriteIds.value = E2eFixtureState.favoriteIds.value + canyonId
    }

    override suspend fun removeFavorite(canyonId: Int) {
        E2eFixtureState.favoriteIds.value = E2eFixtureState.favoriteIds.value - canyonId
    }

    override fun isFavorite(canyonId: Int): Flow<Boolean> = E2eFixtureState.favoriteIds.map { it.contains(canyonId) }
}
