package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.repository.FavoritesRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

class ToggleFavoriteUseCase @Inject constructor(private val favoritesRepository: FavoritesRepository) {
    suspend operator fun invoke(canyonId: Int) {
        val isFav = favoritesRepository.isFavorite(canyonId).first()
        if (isFav) {
            favoritesRepository.removeFavorite(canyonId)
        } else {
            favoritesRepository.addFavorite(canyonId)
        }
    }
}
