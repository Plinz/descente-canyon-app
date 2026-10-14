package fr.descentecanyon.app.data.remote.auth

import fr.descentecanyon.app.data.network.SiteCookieJar
import fr.descentecanyon.app.data.remote.SiteUrls
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.AppException
import fr.descentecanyon.app.testutil.FakeSiteHttpClient
import fr.descentecanyon.app.testutil.InMemoryCredentialStore
import fr.descentecanyon.app.testutil.SitePages
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SessionManagerTest {

    private val siteClient = FakeSiteHttpClient()
    private val cookieJar = SiteCookieJar()
    private val store = InMemoryCredentialStore()
    private val sessionManager = SessionManager(siteClient, cookieJar, store)
    private val siteUrl = SiteUrls.HOME.toHttpUrl()

    @Test
    fun `initial state is anonymous`() {
        assertEquals(SessionState.Anonymous, sessionManager.state.value)
        assertFalse(sessionManager.isLoggedIn)
        assertNull(sessionManager.loggedInUsername)
    }

    @Test
    fun `login succeeds when the site shows the username and saves the session`() = runTest {
        siteClient.onPost(SiteUrls.LOGIN, SitePages.loggedIn("Antoine"))

        assertEquals("Antoine", sessionManager.login("Antoine", "secret"))

        assertEquals(SessionState.Active("Antoine"), sessionManager.state.value)
        assertEquals("Antoine", store.storedUsername)
        assertEquals(mapOf("username" to "Antoine", "password" to "secret"), siteClient.posts.single().fields)
    }

    @Test
    fun `login fails with invalid credentials when the login form is still shown`() = runTest {
        siteClient.onPost(SiteUrls.LOGIN, SitePages.anonymous())

        assertEquals(AppError.InvalidCredentials, loginFailure("Antoine"))
        assertEquals(SessionState.Anonymous, sessionManager.state.value)
        assertNull(store.storedUsername)
    }

    @Test
    fun `a username that appears in an anonymous navbar is not a success`() = runTest {
        // "S'identifier" contains "identifier": the old check accepted this page for that username.
        siteClient.onPost(SiteUrls.LOGIN, SitePages.anonymous())

        assertEquals(AppError.InvalidCredentials, loginFailure("identifier"))
    }

    @Test
    fun `login fails on a page that is not a regular site page`() = runTest {
        siteClient.onPost(SiteUrls.LOGIN, SitePages.NOT_A_SITE_PAGE)

        assertEquals(AppError.UnexpectedResponse, loginFailure("Antoine"))
        assertFalse(sessionManager.isLoggedIn)
    }

    @Test
    fun `restore loads the saved cookies into the jar`() {
        store.saveSession("antoine", listOf(Cookie.parse(siteUrl, "sid=abc; Secure")!!))

        assertTrue(sessionManager.restoreSession())

        assertEquals(SessionState.Active("antoine"), sessionManager.state.value)
        assertEquals(listOf("sid"), cookieJar.loadForRequest(siteUrl).map { it.name })
    }

    @Test
    fun `restore without cookies keeps the user anonymous`() {
        store.storedUsername = "antoine"

        assertFalse(sessionManager.restoreSession())
        assertEquals(SessionState.Anonymous, sessionManager.state.value)
    }

    @Test
    fun `validation marks the session expired when the site shows the login form`() = runTest {
        store.saveSession("antoine", listOf(Cookie.parse(siteUrl, "sid=abc; Secure")!!))
        sessionManager.restoreSession()
        siteClient.onGet(SiteUrls.HOME, SitePages.anonymous())

        assertEquals(SessionState.Expired("antoine"), sessionManager.validateSession())

        assertTrue(cookieJar.snapshot().isEmpty())
        assertTrue(store.storedCookies.isEmpty())
        assertEquals("antoine", store.storedUsername)
    }

    @Test
    fun `validation keeps the session when the answer cannot be read`() = runTest {
        store.saveSession("antoine", listOf(Cookie.parse(siteUrl, "sid=abc; Secure")!!))
        sessionManager.restoreSession()
        siteClient.onGet(SiteUrls.HOME, SitePages.NOT_A_SITE_PAGE)

        assertEquals(SessionState.Active("antoine"), sessionManager.validateSession())
    }

    @Test
    fun `rotated session cookies are persisted while the session is open`() = runTest {
        siteClient.onPost(SiteUrls.LOGIN, SitePages.loggedIn("Antoine"))
        sessionManager.login("Antoine", "secret")

        cookieJar.saveFromResponse(siteUrl, listOf(Cookie.parse(siteUrl, "sid=rotated; Secure")!!))

        assertEquals(listOf("rotated"), store.storedCookies.map { it.value })
    }

    @Test
    fun `logout forgets the session and the username`() = runTest {
        siteClient.onPost(SiteUrls.LOGIN, SitePages.loggedIn("Antoine"))
        sessionManager.login("Antoine", "secret")

        sessionManager.logout()

        assertEquals(SessionState.Anonymous, sessionManager.state.value)
        assertNull(store.storedUsername)
    }

    private suspend fun loginFailure(username: String): AppError {
        try {
            sessionManager.login(username, "secret")
        } catch (exception: AppException) {
            return exception.error
        }
        fail("Expected the login to fail")
        throw AssertionError()
    }
}
