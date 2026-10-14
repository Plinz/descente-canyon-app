package fr.descentecanyon.app.domain.model

import fr.descentecanyon.app.domain.error.AppError

/**
 * Represents the user's authentication state.
 */
sealed interface AuthState {
    data object Disconnected : AuthState
    data object Loading : AuthState
    data class Connected(val username: String) : AuthState

    /** The saved session is no longer accepted by the site: the user must log in again. */
    data class SessionExpired(val username: String) : AuthState
    data class Error(val error: AppError) : AuthState
}
