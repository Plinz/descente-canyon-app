package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.KeyValueDao
import fr.descentecanyon.app.data.local.user.dao.MySiteReportDao
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
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
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Reads the member pages of the site with the open session; nothing is ever posted. */
@Singleton
class SiteAccountRepositoryImpl @Inject constructor(
    private val siteClient: SiteHttpClient,
    private val sessionManager: SessionManager,
    private val mySiteReportDao: MySiteReportDao,
    private val keyValueDao: KeyValueDao,
    private val clock: Clock,
) : SiteAccountRepository {

    override suspend fun refreshMyObservations(): Result<List<SiteObservation>> = suspendRunCatching {
        if (!sessionManager.isLoggedIn) sessionManager.failAsAnonymous()
        // Every year at once: the site warns that this page can be slow.
        val page = siteClient.get(SiteUrls.MY_OBSERVATIONS, timeoutMs = SLOW_PAGE_TIMEOUT_MS)
        if (SiteSessionInspector.inspect(page.document, sessionManager.loggedInUsername) == SiteSessionInspector.Status.LOGGED_OUT) {
            sessionManager.failAsAnonymous()
        }
        if (!SiteObservationsParser.isObservationsPage(page.document)) appError(AppError.UnexpectedResponse)
        parsing { SiteObservationsParser.parse(page.document) }.also { observations ->
            val now = clock.millis()
            mySiteReportDao.replaceAll(observations.map { it.toEntity(fetchedAt = now) })
            // Kept apart from the reports: an account with none has been read too.
            sessionManager.loggedInUsername?.let { username -> keyValueDao.put(KeyValueEntity(READ_AT_KEY, "$username|$now", now)) }
        }
    }

    override suspend fun myObservationsReadAt(): Long? {
        val (username, readAt) = keyValueDao.get(READ_AT_KEY)?.split('|', limit = 2) ?: return null
        // The copy of another account (switched without logging out) is not this account's.
        return readAt.toLongOrNull()?.takeIf { username == sessionManager.loggedInUsername }
    }

    override suspend fun clearMyObservations() {
        mySiteReportDao.deleteAll()
        keyValueDao.delete(READ_AT_KEY)
    }

    private companion object {
        const val SLOW_PAGE_TIMEOUT_MS = 60_000L
        const val READ_AT_KEY = "site_reports_read_at"
    }
}
