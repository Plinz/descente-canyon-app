package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.AuthState
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for authentication with descente-canyon.com.
 */
interface AuthRepository {

    /** Observe the session state: [AuthState.Disconnected], [AuthState.Connected] or [AuthState.SessionExpired]. */
    val authState: Flow<AuthState>

    /** Login with username and password; fails with a typed error. */
    suspend fun login(username: String, password: String): Result<Unit>

    /** Logout and clear session. */
    suspend fun logout()

    /** Restore the saved session without network access; fails when there is none. */
    suspend fun tryRestoreSession(): Result<Unit>

    /** Ask the site whether the restored session is still valid (network). */
    suspend fun validateSession(): Result<Unit>

    /** Username of the last session, to prefill the login form. */
    fun rememberedUsername(): String?
}
