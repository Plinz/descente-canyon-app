package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.InterestRatingSubmission
import fr.descentecanyon.app.domain.repository.InterestRatingRepository
import javax.inject.Inject

class SubmitCanyonInterestRatingUseCase @Inject constructor(private val repository: InterestRatingRepository) {
    suspend operator fun invoke(submission: InterestRatingSubmission): Result<Unit> = repository.submit(submission)
}
