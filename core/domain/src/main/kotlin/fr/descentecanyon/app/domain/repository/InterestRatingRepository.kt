package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CanyonInterestRating
import fr.descentecanyon.app.domain.model.InterestRatingSubmission

interface InterestRatingRepository {
    suspend fun get(canyonId: Int): Result<CanyonInterestRating>
    suspend fun submit(submission: InterestRatingSubmission): Result<Unit>
}
