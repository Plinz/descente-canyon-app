package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.SiteObservation

/** Pages of descente-canyon.com that only the logged-in member sees. */
interface SiteAccountRepository {
    /**
     * Reads every flow report of the member ("Ma liste de courses"), newest first, and keeps them as
     * a read-only copy of the site (the logbook shows them). Fails with AuthenticationRequired or
     * SessionExpired without a valid session.
     */
    suspend fun refreshMyObservations(): Result<List<SiteObservation>>

    /** When the copy of the reports of the logged-in account was last read from the site, null if never. */
    suspend fun myObservationsReadAt(): Long?

    /** Forgets the copy of the account's reports (logout). */
    suspend fun clearMyObservations()

    /**
     * The e-mail address of the logged-in account, read from its account page once and kept; null
     * when the page does not show one. Fails like [refreshMyObservations] without a valid session.
     */
    suspend fun accountEmail(): Result<String?>
}
