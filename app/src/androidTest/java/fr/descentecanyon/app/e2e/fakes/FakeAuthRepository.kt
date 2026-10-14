package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.failure
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.repository.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class FakeAuthRepository @Inject constructor() : AuthRepository {
    override val authState: Flow<AuthState> = E2eFixtureState.authState

    override suspend fun login(username: String, password: String): Result<Unit> {
        E2eFixtureState.authState.value = AuthState.Connected(username)
        return Result.success(Unit)
    }

    override suspend fun logout() {
        E2eFixtureState.authState.value = AuthState.Disconnected
    }

    override suspend fun tryRestoreSession(): Result<Unit> = failure(AppError.AuthenticationRequired)

    override suspend fun validateSession(): Result<Unit> = Result.success(Unit)

    override fun rememberedUsername(): String? = null
}
