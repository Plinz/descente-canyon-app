package fr.descentecanyon.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.error.toAppError
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.ui.common.UiText
import fr.descentecanyon.app.ui.common.toUiText
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val authState: AuthState = AuthState.Disconnected,
    val username: String = "",
    val password: String = "",
    /** Error or prompt displayed above the login form. */
    val message: UiText? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(private val authRepository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState(username = authRepository.rememberedUsername().orEmpty()))
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        observeAuthState()
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            authRepository.authState.collect { state ->
                _uiState.update { current ->
                    when (state) {
                        is AuthState.SessionExpired -> current.copy(
                            authState = state,
                            username = current.username.ifBlank { state.username },
                            message = UiText.res(R.string.error_session_expired),
                        )

                        is AuthState.Connected -> current.copy(authState = state, message = null)

                        else -> current.copy(authState = state)
                    }
                }
            }
        }
    }

    fun onUsernameChanged(username: String) {
        _uiState.update { it.copy(username = username) }
    }

    fun onPasswordChanged(password: String) {
        _uiState.update { it.copy(password = password) }
    }

    /** Shows why a login is needed (e.g. an expired session detected while submitting). */
    fun showMessage(message: UiText) {
        _uiState.update { it.copy(message = message) }
    }

    fun login() {
        val state = _uiState.value
        if (state.username.isBlank() || state.password.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(authState = AuthState.Loading, message = null) }
            authRepository.login(state.username, state.password).fold(
                onSuccess = {
                    _uiState.update { it.copy(password = "") }
                },
                onFailure = { throwable ->
                    val error = throwable.toAppError()
                    _uiState.update {
                        it.copy(
                            authState = AuthState.Error(error),
                            message = error.toUiText(fallback = R.string.login_error),
                        )
                    }
                },
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _uiState.update {
                it.copy(
                    username = "",
                    password = "",
                    message = null,
                )
            }
        }
    }
}
