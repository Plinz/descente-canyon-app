package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.CanyonInterestRating
import fr.descentecanyon.app.domain.repository.InterestRatingRepository
import javax.inject.Inject

class GetCanyonInterestRatingUseCase @Inject constructor(private val repository: InterestRatingRepository) {
    suspend operator fun invoke(canyonId: Int): Result<CanyonInterestRating> = repository.get(canyonId)
}
