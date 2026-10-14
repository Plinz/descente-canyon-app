package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.network.SiteCookieJar
import fr.descentecanyon.app.data.remote.SiteUrls
import fr.descentecanyon.app.data.remote.auth.SessionManager
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appErrorOrNull
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.testutil.FakeSiteHttpClient
import fr.descentecanyon.app.testutil.InMemoryCredentialStore
import fr.descentecanyon.app.testutil.SitePages
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryImplTest {

    private val siteClient = FakeSiteHttpClient()
    private val store = InMemoryCredentialStore()
    private val repository = AuthRepositoryImpl(SessionManager(siteClient, SiteCookieJar(), store))

    @Test
    fun `initial state is disconnected`() = runTest {
        assertEquals(AuthState.Disconnected, repository.authState.first())
    }

    @Test
    fun `login failure is typed and keeps the user disconnected`() = runTest {
        siteClient.onPost(SiteUrls.LOGIN, SitePages.anonymous())

        val result = repository.login("user", "wrong")

        assertEquals(AppError.InvalidCredentials, result.appErrorOrNull())
        assertEquals(AuthState.Disconnected, repository.authState.first())
    }

    @Test
    fun `login success connects the user`() = runTest {
        siteClient.onPost(SiteUrls.LOGIN, SitePages.loggedIn("antoine"))

        assertTrue(repository.login(" antoine ", "secret").isSuccess)
        assertEquals(AuthState.Connected("antoine"), repository.authState.first())
    }

    @Test
    fun `logout clears the stored session`() = runTest {
        siteClient.onPost(SiteUrls.LOGIN, SitePages.loggedIn("antoine"))
        repository.login("antoine", "secret")

        repository.logout()

        assertEquals(AuthState.Disconnected, repository.authState.first())
        assertNull(store.storedUsername)
    }

    @Test
    fun `restore fails without a saved session`() = runTest {
        assertEquals(AppError.AuthenticationRequired, repository.tryRestoreSession().appErrorOrNull())
    }

    @Test
    fun `restore reconnects from saved cookies without network`() = runTest {
        store.saveSession("antoine", listOf(Cookie.parse(SiteUrls.HOME.toHttpUrl(), "sid=abc; Secure")!!))

        assertTrue(repository.tryRestoreSession().isSuccess)
        assertEquals(AuthState.Connected("antoine"), repository.authState.first())
        assertTrue(siteClient.gets.isEmpty())
    }

    @Test
    fun `an expired session is reported by validation`() = runTest {
        store.saveSession("antoine", listOf(Cookie.parse(SiteUrls.HOME.toHttpUrl(), "sid=abc; Secure")!!))
        repository.tryRestoreSession()
        siteClient.onGet(SiteUrls.HOME, SitePages.anonymous())

        assertEquals(AppError.SessionExpired, repository.validateSession().appErrorOrNull())
        assertEquals(AuthState.SessionExpired("antoine"), repository.authState.first())
        assertEquals("antoine", repository.rememberedUsername())
    }
}
