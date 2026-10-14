package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.remote.auth.SessionManager
import fr.descentecanyon.app.data.remote.auth.SessionState
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import fr.descentecanyon.app.domain.error.failure
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.repository.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class AuthRepositoryImpl @Inject constructor(private val sessionManager: SessionManager) : AuthRepository {

    override val authState: Flow<AuthState> = sessionManager.state.map { state ->
        when (state) {
            SessionState.Anonymous -> AuthState.Disconnected
            is SessionState.Active -> AuthState.Connected(state.username)
            is SessionState.Expired -> AuthState.SessionExpired(state.username)
        }
    }

    override suspend fun login(username: String, password: String): Result<Unit> = suspendRunCatching {
        sessionManager.login(username.trim(), password)
        Unit
    }

    override suspend fun logout() {
        sessionManager.logout()
    }

    override suspend fun tryRestoreSession(): Result<Unit> =
        if (sessionManager.restoreSession()) Result.success(Unit) else failure(AppError.AuthenticationRequired)

    override suspend fun validateSession(): Result<Unit> = suspendRunCatching {
        when (sessionManager.validateSession()) {
            is SessionState.Active -> Unit
            is SessionState.Expired -> appError(AppError.SessionExpired)
            SessionState.Anonymous -> appError(AppError.AuthenticationRequired)
        }
    }

    override fun rememberedUsername(): String? = sessionManager.rememberedUsername()
}
