package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.model.CanyonInterestRating
import fr.descentecanyon.app.domain.model.InterestRatingSubmission
import fr.descentecanyon.app.domain.repository.InterestRatingRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeInterestRatingRepository @Inject constructor() : InterestRatingRepository {
    override suspend fun get(canyonId: Int): Result<CanyonInterestRating> = Result.success(CanyonInterestRating(canyonId = canyonId))

    override suspend fun submit(submission: InterestRatingSubmission): Result<Unit> = Result.success(Unit)
}
