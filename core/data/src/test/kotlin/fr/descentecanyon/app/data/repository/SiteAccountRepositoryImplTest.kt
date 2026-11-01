package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.MySiteReportDao
import fr.descentecanyon.app.data.local.user.entity.MySiteReportEntity
import fr.descentecanyon.app.data.network.SiteCookieJar
import fr.descentecanyon.app.data.remote.SiteUrls
import fr.descentecanyon.app.data.remote.auth.SessionManager
import fr.descentecanyon.app.data.remote.auth.SessionState
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appErrorOrNull
import fr.descentecanyon.app.testutil.FakeSiteHttpClient
import fr.descentecanyon.app.testutil.InMemoryCredentialStore
import fr.descentecanyon.app.testutil.InMemoryKeyValueDao
import fr.descentecanyon.app.testutil.SitePages
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteAccountRepositoryImplTest {

    private val siteClient = FakeSiteHttpClient()
    private val store = InMemoryCredentialStore()
    private val sessionManager = SessionManager(siteClient, SiteCookieJar(), store)
    private val mySiteReportDao = mockk<MySiteReportDao>(relaxed = true)
    private val keyValueDao = InMemoryKeyValueDao()
    private val repository =
        SiteAccountRepositoryImpl(
            siteClient,
            sessionManager,
            mySiteReportDao,
            keyValueDao,
            Clock.fixed(Instant.parse("2026-10-31T09:00:00Z"), ZoneOffset.UTC),
        )
    private val listPage = requireNotNull(javaClass.getResource("/html/perso_observations.html")).readText()

    private fun logIn() {
        store.saveSession("Membre", listOf(Cookie.parse(SiteUrls.HOME.toHttpUrl(), "sid=abc; Secure")!!))
        sessionManager.restoreSession()
    }

    @Test
    fun `reads the list of the logged-in member`() = runTest {
        logIn()
        siteClient.onGet(SiteUrls.MY_OBSERVATIONS, listPage)

        val observations = repository.refreshMyObservations().getOrThrow()

        assertEquals(3, observations.size)
        assertEquals(listOf(SiteUrls.MY_OBSERVATIONS), siteClient.gets)
        assertTrue(siteClient.posts.isEmpty())
    }

    @Test
    fun `the list read replaces the copy kept for the logbook`() = runTest {
        logIn()
        siteClient.onGet(SiteUrls.MY_OBSERVATIONS, listPage)
        val kept = slot<List<MySiteReportEntity>>()
        coEvery { mySiteReportDao.replaceAll(capture(kept)) } returns Unit

        val observations = repository.refreshMyObservations().getOrThrow()

        assertEquals(observations.map { it.id }, kept.captured.map { it.id })
        assertEquals(setOf(Instant.parse("2026-10-31T09:00:00Z").toEpochMilli()), kept.captured.map { it.fetchedAt }.toSet())
    }

    @Test
    fun `a failed reading leaves the copy as it was`() = runTest {
        val error = repository.refreshMyObservations().appErrorOrNull()

        assertEquals(AppError.AuthenticationRequired, error)
        coVerify(exactly = 0) { mySiteReportDao.replaceAll(any()) }
    }

    @Test
    fun `the copy can be forgotten`() = runTest {
        logIn()
        siteClient.onGet(SiteUrls.MY_OBSERVATIONS, listPage)
        repository.refreshMyObservations().getOrThrow()

        repository.clearMyObservations()

        coVerify(exactly = 1) { mySiteReportDao.deleteAll() }
        assertNull(repository.myObservationsReadAt())
    }

    @Test
    fun `the time of the reading is kept for the account read`() = runTest {
        assertNull(repository.myObservationsReadAt())
        logIn()
        siteClient.onGet(SiteUrls.MY_OBSERVATIONS, listPage)

        repository.refreshMyObservations().getOrThrow()

        assertEquals(Instant.parse("2026-10-31T09:00:00Z").toEpochMilli(), repository.myObservationsReadAt())
        // Another account logged in afterwards has not been read.
        store.saveSession("Autre", listOf(Cookie.parse(SiteUrls.HOME.toHttpUrl(), "sid=def; Secure")!!))
        sessionManager.restoreSession()
        assertNull(repository.myObservationsReadAt())
    }

    @Test
    fun `without a session the site is not even asked`() = runTest {
        val error = repository.refreshMyObservations().appErrorOrNull()

        assertEquals(AppError.AuthenticationRequired, error)
        assertTrue(siteClient.gets.isEmpty())
    }

    @Test
    fun `an anonymous answer marks the session expired`() = runTest {
        logIn()
        siteClient.onGet(SiteUrls.MY_OBSERVATIONS, SitePages.anonymous("<h1>Identification</h1>"))

        val error = repository.refreshMyObservations().appErrorOrNull()

        assertEquals(AppError.SessionExpired, error)
        assertEquals(SessionState.Expired("Membre"), sessionManager.state.value)
    }

    @Test
    fun `a page that is not the list is an unexpected answer`() = runTest {
        logIn()
        siteClient.onGet(SiteUrls.MY_OBSERVATIONS, SitePages.loggedIn("Membre", "<h1>Maintenance</h1>"))

        assertEquals(AppError.UnexpectedResponse, repository.refreshMyObservations().appErrorOrNull())
    }
}
