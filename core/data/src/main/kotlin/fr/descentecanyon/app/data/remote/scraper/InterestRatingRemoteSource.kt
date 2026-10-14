package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.domain.model.CanyonInterestRating
import fr.descentecanyon.app.domain.model.InterestRatingSubmission

interface InterestRatingRemoteSource {
    suspend fun get(canyonId: Int): Result<CanyonInterestRating>
    suspend fun submit(submission: InterestRatingSubmission): Result<Unit>
}
