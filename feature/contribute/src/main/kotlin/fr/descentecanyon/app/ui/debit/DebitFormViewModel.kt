package fr.descentecanyon.app.ui.debit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.error.requiresLogin
import fr.descentecanyon.app.domain.error.suspendRunCatching
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
import fr.descentecanyon.app.domain.repository.CanyonRepository
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
    /** The canyon reported on, shown so a report from a notification cannot go to the wrong one. */
    val canyonName: String? = null,
    val canyonPlace: String? = null,
    val observerName: String = "",
    val observerEmail: String = "",
    val observationDate: LocalDate = LocalDate.now(),
    val observationType: ObservationType? = null,
    val debitLevel: NiveauDebit? = null,
    val waterTemperature: WaterTemperature? = null,
    val airTemperature: AirTemperature? = null,
    val comment: String = "",
    val personalComment: String = "",
    /** Logged in to the site account (the report then carries its name). */
    val isConnected: Boolean = false,
    /** The network is there: without it the report waits in the queue. */
    val isOnline: Boolean = true,
    /** The name and e-mail of the last report sent without an account were filled in: they fold into one line. */
    val hasRememberedIdentity: Boolean = false,
    val pendingCount: Int = 0,
    val isSubmitting: Boolean = false,
    /** The report is complete: the person reads it one last time before it is published. */
    val isConfirming: Boolean = false,
    val error: UiText? = null,
    val transientMessage: UiText? = null,
    val lastSubmissionStatus: DebitSubmissionStatus? = null,
    val loginRequiredMessage: UiText? = null,
)

@HiltViewModel
class DebitFormViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
    private val submitDebitUseCase: SubmitDebitUseCase,
    private val debitSubmissionRepository: DebitSubmissionRepository,
    private val preferencesRepository: PreferencesRepository,
    private val canyonRepository: CanyonRepository,
    private val connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    private val canyonId: Int = checkNotNull(savedStateHandle["canyonId"])

    // Restored after Android stopped the app in the background (camera, maps on the way back...).
    private val _uiState = MutableStateFlow(savedStateHandle.restoreForm(savedStateHandle.prefilledForm(canyonId)))
    val uiState: StateFlow<DebitFormUiState> = _uiState.asStateFlow()

    init {
        observeAuth()
        observeConnectivity()
        observePendingCount()
        prefillContributorIdentity()
        loadCanyon()
        viewModelScope.launch { _uiState.collect { state -> savedStateHandle.saveForm(state) } }
    }

    private fun loadCanyon() {
        viewModelScope.launch {
            val canyon =
                suspendRunCatching { canyonRepository.getCanyonSummaries(setOf(canyonId)).firstOrNull() }.getOrNull() ?: return@launch
            _uiState.update {
                it.copy(canyonName = canyon.nom, canyonPlace = listOfNotNull(canyon.departement, canyon.pays).joinToString(" · "))
            }
        }
    }

    /** Offers the name and e-mail of the last report sent without an account. */
    private fun prefillContributorIdentity() {
        viewModelScope.launch {
            val identity = preferencesRepository.getContributorIdentity() ?: return@launch
            _uiState.update { state ->
                if (state.isConnected) {
                    state
                } else {
                    val name = state.observerName.ifBlank { identity.name }
                    val email = state.observerEmail.ifBlank { identity.email.orEmpty() }
                    state.copy(
                        observerName = name,
                        observerEmail = email,
                        hasRememberedIdentity = name == identity.name && email == identity.email && name.isNotBlank(),
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

    private fun observeConnectivity() {
        viewModelScope.launch {
            connectivityObserver.observe().collect { online -> _uiState.update { it.copy(isOnline = online) } }
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

    /** Checks the form, then asks for a last reading: nothing is published before [confirmSubmit]. */
    fun requestSubmit() {
        val state = _uiState.value
        if (state.isSubmitting) return
        validate(state)?.let { error ->
            _uiState.update { it.copy(error = error) }
            return
        }
        _uiState.update { it.copy(isConfirming = true, error = null) }
    }

    fun cancelConfirmation() {
        _uiState.update { it.copy(isConfirming = false) }
    }

    fun confirmSubmit() {
        val state = _uiState.value
        // A second tap while the report is being sent would send it twice.
        if (state.isSubmitting || !state.isConfirming) return
        // The form can change nothing between the reading and the confirmation, but check once more.
        validate(state)?.let { error ->
            _uiState.update { it.copy(isConfirming = false, error = error) }
            return
        }
        val observerName = state.observerName.trim()
        val observerEmail = state.observerEmail.trim().takeIf { it.isNotBlank() }
        val observationType = checkNotNull(state.observationType)
        val debitLevel = checkNotNull(state.debitLevel)

        _uiState.update { it.copy(isSubmitting = true, isConfirming = false, error = null, transientMessage = null) }
        viewModelScope.launch {
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

    /** What keeps the report from being sent, or null when it is complete. */
    private fun validate(state: DebitFormUiState): UiText? {
        val observerEmail = state.observerEmail.trim().takeIf { it.isNotBlank() }
        return when {
            state.observerName.isBlank() -> UiText.res(R.string.debit_observer_name_required)

            !state.isConnected && observerEmail == null -> UiText.res(R.string.debit_observer_email_required_offline)

            // Caught now: a report stored offline would only be refused by the site later.
            !state.isConnected && observerEmail?.matches(PLAUSIBLE_EMAIL) != true -> UiText.res(R.string.debit_observer_email_invalid)

            state.observationType == null -> UiText.res(R.string.debit_observation_type_required)

            state.debitLevel == null -> UiText.res(R.string.debit_level_required)

            else -> null
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

    private companion object {
        const val FORM_NAME = "form.name"
        const val FORM_EMAIL = "form.email"
        const val FORM_DATE = "form.date"
        const val FORM_TYPE = "form.type"
        const val FORM_LEVEL = "form.level"
        const val FORM_WATER = "form.water"
        const val FORM_AIR = "form.air"
        const val FORM_COMMENT = "form.comment"
        const val FORM_PERSONAL_COMMENT = "form.personalComment"

        fun SavedStateHandle.saveForm(state: DebitFormUiState) {
            this[FORM_NAME] = state.observerName
            this[FORM_EMAIL] = state.observerEmail
            this[FORM_DATE] = state.observationDate.toString()
            this[FORM_TYPE] = state.observationType?.name
            this[FORM_LEVEL] = state.debitLevel?.name
            this[FORM_WATER] = state.waterTemperature?.name
            this[FORM_AIR] = state.airTemperature?.name
            this[FORM_COMMENT] = state.comment
            this[FORM_PERSONAL_COMMENT] = state.personalComment
        }

        /**
         * Only the day of an outing already logged is offered: what was seen (descended or not, the
         * flow) is chosen by the person, since it is published under their name.
         */
        fun SavedStateHandle.prefilledForm(canyonId: Int): DebitFormUiState {
            val initial = DebitFormUiState(canyonId = canyonId)
            return initial.copy(
                observationDate = get<String>("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: initial.observationDate,
            )
        }

        fun SavedStateHandle.restoreForm(initial: DebitFormUiState): DebitFormUiState = initial.copy(
            observerName = get<String>(FORM_NAME) ?: initial.observerName,
            observerEmail = get<String>(FORM_EMAIL) ?: initial.observerEmail,
            observationDate = get<String>(FORM_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: initial.observationDate,
            observationType = get<String>(FORM_TYPE)?.let { name -> ObservationType.entries.firstOrNull { it.name == name } }
                ?: initial.observationType,
            debitLevel = get<String>(FORM_LEVEL)?.let { name -> NiveauDebit.entries.firstOrNull { it.name == name } } ?: initial.debitLevel,
            waterTemperature = get<String>(FORM_WATER)?.let { name -> WaterTemperature.entries.firstOrNull { it.name == name } },
            airTemperature = get<String>(FORM_AIR)?.let { name -> AirTemperature.entries.firstOrNull { it.name == name } },
            comment = get<String>(FORM_COMMENT) ?: initial.comment,
            personalComment = get<String>(FORM_PERSONAL_COMMENT) ?: initial.personalComment,
        )

        val PLAUSIBLE_EMAIL = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""")
    }
}
