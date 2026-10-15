package fr.descentecanyon.app.data.remote.auth

import fr.descentecanyon.app.data.network.SiteCookieJar
import fr.descentecanyon.app.data.network.SiteHttpClient
import fr.descentecanyon.app.data.remote.SiteUrls
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.AppException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** State of the descente-canyon.com session. */
sealed interface SessionState {
    data object Anonymous : SessionState
    data class Active(val username: String) : SessionState

    /** The site no longer recognizes the saved session; the user has to log in again. */
    data class Expired(val username: String) : SessionState
}

/**
 * Single owner of the descente-canyon.com session: logs in (POST /login with "username" and
 * "password"), persists the session cookies while the session is open and detects its expiry.
 */
@Singleton
class SessionManager @Inject constructor(
    private val siteClient: SiteHttpClient,
    private val cookieJar: SiteCookieJar,
    private val credentialStore: CredentialStore,
) {

    private val loginMutex = Mutex()
    private val _state = MutableStateFlow<SessionState>(SessionState.Anonymous)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val loggedInUsername: String? get() = (_state.value as? SessionState.Active)?.username
    val isLoggedIn: Boolean get() = _state.value is SessionState.Active

    init {
        cookieJar.onCookiesChanged = { cookies ->
            // The site may rotate its session cookie: keep the stored copy current.
            if (_state.value is SessionState.Active) credentialStore.updateSessionCookies(cookies)
        }
    }

    /**
     * Logs in and returns the username.
     * @throws AppException [AppError.InvalidCredentials] when the site still shows the login form,
     * [AppError.UnexpectedResponse] when the answer cannot be interpreted, or a transport error.
     */
    suspend fun login(username: String, password: String): String = loginMutex.withLock {
        cookieJar.clear()
        val page = try {
            siteClient.postForm(
                url = SiteUrls.LOGIN,
                fields = mapOf("username" to username, "password" to password),
            )
        } catch (exception: Exception) {
            cookieJar.clear()
            throw exception
        }
        when (SiteSessionInspector.inspect(page.document, username)) {
            SiteSessionInspector.Status.LOGGED_IN -> {
                credentialStore.saveSession(username, cookieJar.snapshot())
                _state.value = SessionState.Active(username)
                username
            }

            SiteSessionInspector.Status.LOGGED_OUT -> {
                cookieJar.clear()
                throw AppException(AppError.InvalidCredentials)
            }

            SiteSessionInspector.Status.UNKNOWN -> {
                cookieJar.clear()
                throw AppException(AppError.UnexpectedResponse)
            }
        }
    }

    /** Restores the persisted session without network access; returns whether one was found. */
    fun restoreSession(): Boolean {
        val username = credentialStore.getUsername() ?: return false
        val cookies = credentialStore.getSessionCookies()
        if (cookies.isEmpty()) return false
        cookieJar.replaceAll(cookies)
        _state.value = SessionState.Active(username)
        return true
    }

    /**
     * Asks the site whether the open session is still valid and marks it expired otherwise.
     * An unreadable answer (maintenance page...) keeps the session.
     */
    suspend fun validateSession(): SessionState {
        val active = _state.value as? SessionState.Active ?: return _state.value
        val page = siteClient.get(SiteUrls.HOME)
        if (SiteSessionInspector.inspect(page.document, active.username) == SiteSessionInspector.Status.LOGGED_OUT) {
            markExpired()
        }
        return _state.value
    }

    /** Called when a page answered as if the user were anonymous although a session was open. */
    fun markExpired() {
        val username = loggedInUsername ?: return
        cookieJar.clear()
        credentialStore.clearSession(keepUsername = true)
        _state.value = SessionState.Expired(username)
    }

    fun logout() {
        cookieJar.clear()
        credentialStore.clearSession(keepUsername = false)
        _state.value = SessionState.Anonymous
    }

    /** Username remembered from the last session, used to prefill the login form. */
    fun rememberedUsername(): String? = credentialStore.getUsername()
}
