package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.network.SiteHttpClient
import fr.descentecanyon.app.data.remote.SiteUrls
import fr.descentecanyon.app.data.remote.auth.SessionManager
import fr.descentecanyon.app.data.remote.auth.SiteSessionInspector
import fr.descentecanyon.app.data.remote.scraper.SiteObservationsParser
import fr.descentecanyon.app.data.remote.scraper.failAsAnonymous
import fr.descentecanyon.app.data.remote.scraper.parsing
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.SiteObservation
import fr.descentecanyon.app.domain.repository.SiteAccountRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Reads the member pages of the site with the open session; nothing is ever posted. */
@Singleton
class SiteAccountRepositoryImpl @Inject constructor(private val siteClient: SiteHttpClient, private val sessionManager: SessionManager) :
    SiteAccountRepository {

    override suspend fun fetchMyObservations(): Result<List<SiteObservation>> = suspendRunCatching {
        if (!sessionManager.isLoggedIn) sessionManager.failAsAnonymous()
        // Every year at once: the site warns that this page can be slow.
        val page = siteClient.get(SiteUrls.MY_OBSERVATIONS, timeoutMs = SLOW_PAGE_TIMEOUT_MS)
        if (SiteSessionInspector.inspect(page.document, sessionManager.loggedInUsername) == SiteSessionInspector.Status.LOGGED_OUT) {
            sessionManager.failAsAnonymous()
        }
        if (!SiteObservationsParser.isObservationsPage(page.document)) appError(AppError.UnexpectedResponse)
        parsing { SiteObservationsParser.parse(page.document) }
    }

    private companion object {
        const val SLOW_PAGE_TIMEOUT_MS = 60_000L
    }
}
