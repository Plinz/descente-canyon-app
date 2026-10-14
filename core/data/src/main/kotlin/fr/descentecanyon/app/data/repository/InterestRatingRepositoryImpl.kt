package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.remote.scraper.InterestRatingRemoteSource
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.failure
import fr.descentecanyon.app.domain.model.CanyonInterestRating
import fr.descentecanyon.app.domain.model.InterestRatingSubmission
import fr.descentecanyon.app.domain.repository.InterestRatingRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InterestRatingRepositoryImpl @Inject constructor(
    private val remoteSource: InterestRatingRemoteSource,
    private val connectivityObserver: ConnectivityObserver,
) : InterestRatingRepository {
    override suspend fun get(canyonId: Int): Result<CanyonInterestRating> {
        if (!connectivityObserver.isCurrentlyOnline()) {
            return failure(AppError.Offline)
        }
        return remoteSource.get(canyonId)
    }

    override suspend fun submit(submission: InterestRatingSubmission): Result<Unit> {
        if (!connectivityObserver.isCurrentlyOnline()) {
            return failure(AppError.Offline)
        }
        return remoteSource.submit(submission)
    }
}
