package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.SiteObservation

/** Pages of descente-canyon.com that only the logged-in member sees. */
interface SiteAccountRepository {
    /**
     * Every flow report of the member ("Ma liste de courses"), newest first. Fails with
     * AuthenticationRequired or SessionExpired without a valid session.
     */
    suspend fun fetchMyObservations(): Result<List<SiteObservation>>
}
