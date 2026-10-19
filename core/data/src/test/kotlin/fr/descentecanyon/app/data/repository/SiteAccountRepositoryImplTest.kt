package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.network.SiteCookieJar
import fr.descentecanyon.app.data.remote.SiteUrls
import fr.descentecanyon.app.data.remote.auth.SessionManager
import fr.descentecanyon.app.data.remote.auth.SessionState
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appErrorOrNull
import fr.descentecanyon.app.testutil.FakeSiteHttpClient
import fr.descentecanyon.app.testutil.InMemoryCredentialStore
import fr.descentecanyon.app.testutil.SitePages
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteAccountRepositoryImplTest {

    private val siteClient = FakeSiteHttpClient()
    private val store = InMemoryCredentialStore()
    private val sessionManager = SessionManager(siteClient, SiteCookieJar(), store)
    private val repository = SiteAccountRepositoryImpl(siteClient, sessionManager)
    private val listPage = requireNotNull(javaClass.getResource("/html/perso_observations.html")).readText()

    private fun logIn() {
        store.saveSession("Membre", listOf(Cookie.parse(SiteUrls.HOME.toHttpUrl(), "sid=abc; Secure")!!))
        sessionManager.restoreSession()
    }

    @Test
    fun `reads the list of the logged-in member`() = runTest {
        logIn()
        siteClient.onGet(SiteUrls.MY_OBSERVATIONS, listPage)

        val observations = repository.fetchMyObservations().getOrThrow()

        assertEquals(3, observations.size)
        assertEquals(listOf(SiteUrls.MY_OBSERVATIONS), siteClient.gets)
        assertTrue(siteClient.posts.isEmpty())
    }

    @Test
    fun `without a session the site is not even asked`() = runTest {
        val error = repository.fetchMyObservations().appErrorOrNull()

        assertEquals(AppError.AuthenticationRequired, error)
        assertTrue(siteClient.gets.isEmpty())
    }

    @Test
    fun `an anonymous answer marks the session expired`() = runTest {
        logIn()
        siteClient.onGet(SiteUrls.MY_OBSERVATIONS, SitePages.anonymous("<h1>Identification</h1>"))

        val error = repository.fetchMyObservations().appErrorOrNull()

        assertEquals(AppError.SessionExpired, error)
        assertEquals(SessionState.Expired("Membre"), sessionManager.state.value)
    }

    @Test
    fun `a page that is not the list is an unexpected answer`() = runTest {
        logIn()
        siteClient.onGet(SiteUrls.MY_OBSERVATIONS, SitePages.loggedIn("Membre", "<h1>Maintenance</h1>"))

        assertEquals(AppError.UnexpectedResponse, repository.fetchMyObservations().appErrorOrNull())
    }
}
