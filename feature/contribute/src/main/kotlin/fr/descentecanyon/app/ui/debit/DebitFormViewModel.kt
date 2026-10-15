package fr.descentecanyon.app.ui.debit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.error.requiresLogin
import fr.descentecanyon.app.domain.error.toAppError
import fr.descentecanyon.app.domain.model.AirTemperature
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.model.ContributorIdentity
import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.DebitSubmissionStatus
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.model.WaterTemperature
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.domain.repository.PreferencesRepository
import fr.descentecanyon.app.domain.usecase.SubmitDebitUseCase
import fr.descentecanyon.app.ui.common.UiText
import fr.descentecanyon.app.ui.common.toUiText
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DebitFormUiState(
    val canyonId: Int = 0,
    val observerName: String = "",
    val observerEmail: String = "",
    val observationDate: LocalDate = LocalDate.now(),
    val observationType: ObservationType? = null,
    val debitLevel: NiveauDebit? = null,
    val waterTemperature: WaterTemperature? = null,
    val airTemperature: AirTemperature? = null,
    val comment: String = "",
    val personalComment: String = "",
    val isConnected: Boolean = false,
    val pendingCount: Int = 0,
    val isSubmitting: Boolean = false,
    val error: UiText? = null,
    val transientMessage: UiText? = null,
    val lastSubmissionStatus: DebitSubmissionStatus? = null,
    val loginRequiredMessage: UiText? = null,
)

@HiltViewModel
class DebitFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
    private val submitDebitUseCase: SubmitDebitUseCase,
    private val debitSubmissionRepository: DebitSubmissionRepository,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val canyonId: Int = checkNotNull(savedStateHandle["canyonId"])

    private val _uiState = MutableStateFlow(DebitFormUiState(canyonId = canyonId))
    val uiState: StateFlow<DebitFormUiState> = _uiState.asStateFlow()

    init {
        observeAuth()
        observePendingCount()
        prefillContributorIdentity()
    }

    /** Offers the name and e-mail of the last report sent without an account. */
    private fun prefillContributorIdentity() {
        viewModelScope.launch {
            val identity = preferencesRepository.getContributorIdentity() ?: return@launch
            _uiState.update { state ->
                if (state.isConnected) {
                    state
                } else {
                    state.copy(
                        observerName = state.observerName.ifBlank { identity.name },
                        observerEmail = state.observerEmail.ifBlank { identity.email.orEmpty() },
                    )
                }
            }
        }
    }

    private fun observeAuth() {
        viewModelScope.launch {
            authRepository.authState.collect { authState ->
                _uiState.update { state ->
                    when (authState) {
                        is AuthState.Connected -> state.copy(
                            isConnected = true,
                            observerName = authState.username,
                            error = null,
                        )

                        else -> state.copy(isConnected = false)
                    }
                }
            }
        }
    }

    private fun observePendingCount() {
        viewModelScope.launch {
            debitSubmissionRepository.observePendingCount().collect { count ->
                _uiState.update { it.copy(pendingCount = count) }
            }
        }
    }

    fun onObserverNameChanged(value: String) {
        _uiState.update { it.copy(observerName = value, error = null) }
    }

    fun onObserverEmailChanged(value: String) {
        _uiState.update { it.copy(observerEmail = value, error = null) }
    }

    fun onObservationDateChanged(value: String) {
        runCatching { LocalDate.parse(value) }.onSuccess { parsed ->
            onObservationDateSelected(parsed)
        }
    }

    fun onObservationDateSelected(value: LocalDate) {
        _uiState.update { it.copy(observationDate = value, error = null) }
    }

    fun onObservationTypeChanged(value: ObservationType) {
        _uiState.update { it.copy(observationType = value, error = null) }
    }

    fun onDebitLevelChanged(value: NiveauDebit) {
        _uiState.update { it.copy(debitLevel = value, error = null) }
    }

    fun onWaterTemperatureChanged(value: WaterTemperature) {
        _uiState.update { it.copy(waterTemperature = value, error = null) }
    }

    fun onAirTemperatureChanged(value: AirTemperature) {
        _uiState.update { it.copy(airTemperature = value, error = null) }
    }

    fun onCommentChanged(value: String) {
        _uiState.update { it.copy(comment = value, error = null) }
    }

    fun onPersonalCommentChanged(value: String) {
        _uiState.update { it.copy(personalComment = value, error = null) }
    }

    fun submit() {
        val state = _uiState.value
        val observerName = state.observerName.trim()
        val observerEmail = state.observerEmail.trim().takeIf { it.isNotBlank() }
        val observationType = state.observationType
        val debitLevel = state.debitLevel

        when {
            observerName.isBlank() -> {
                _uiState.update { it.copy(error = UiText.res(R.string.debit_observer_name_required)) }
                return
            }
            !state.isConnected && observerEmail == null -> {
                _uiState.update { it.copy(error = UiText.res(R.string.debit_observer_email_required_offline)) }
                return
            }
            observationType == null -> {
                _uiState.update { it.copy(error = UiText.res(R.string.debit_observation_type_required)) }
                return
            }
            debitLevel == null -> {
                _uiState.update { it.copy(error = UiText.res(R.string.debit_level_required)) }
                return
            }
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null, transientMessage = null) }
            submitDebitUseCase(
                DebitSubmission(
                    canyonId = canyonId,
                    observerName = observerName,
                    observerEmail = observerEmail,
                    observationDate = state.observationDate,
                    observationType = observationType,
                    debitLevel = debitLevel,
                    waterTemperature = state.waterTemperature ?: WaterTemperature.INCONNUE,
                    airTemperature = state.airTemperature ?: AirTemperature.INCONNUE,
                    comment = state.comment.trim(),
                    personalComment = state.personalComment.trim(),
                ),
            ).fold(
                onSuccess = { status ->
                    if (!state.isConnected) {
                        preferencesRepository.setContributorIdentity(ContributorIdentity(observerName, observerEmail))
                    }
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            transientMessage = when (status) {
                                DebitSubmissionStatus.SUBMITTED -> UiText.res(R.string.debit_submission_sent)
                                DebitSubmissionStatus.SUBMITTED_UNCONFIRMED -> UiText.res(R.string.debit_submission_sent_unconfirmed)
                                DebitSubmissionStatus.QUEUED_OFFLINE -> UiText.res(R.string.debit_submission_saved_offline)
                            },
                            lastSubmissionStatus = status,
                        )
                    }
                },
                onFailure = { throwable ->
                    val error = throwable.toAppError()
                    val message = if (error.requiresLogin()) {
                        UiText.res(R.string.debit_session_expired)
                    } else {
                        error.toUiText(fallback = R.string.debit_submission_failed)
                    }
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            error = message,
                            loginRequiredMessage = message.takeIf { error.requiresLogin() },
                        )
                    }
                },
            )
        }
    }

    fun clearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    fun clearLastSubmissionStatus() {
        _uiState.update { it.copy(lastSubmissionStatus = null) }
    }

    fun clearLoginRequiredMessage() {
        _uiState.update { it.copy(loginRequiredMessage = null) }
    }
}
