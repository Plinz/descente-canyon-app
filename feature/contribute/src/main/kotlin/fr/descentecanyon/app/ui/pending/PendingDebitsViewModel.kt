package fr.descentecanyon.app.ui.pending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.isTransient
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.model.PendingDebitRejection
import fr.descentecanyon.app.domain.model.PendingDebitSubmission
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PendingDebitItem(
    val id: Long,
    val canyonId: Int,
    val canyonName: String?,
    val observationDate: LocalDate,
    val observationType: ObservationType,
    val debitLevel: NiveauDebit,
    val comment: String,
    val needsLogin: Boolean,
    /** Set when the site refused the report: it waits for the user instead of being sent. */
    val rejection: PendingDebitRejection? = null,
) {
    val isRejected: Boolean get() = rejection != null
}

/** What keeps the queue from being sent right now. */
enum class PendingDebitsStatus {
    EMPTY,
    OFFLINE,
    LOGIN_REQUIRED,
    SENDING,

    /** The last sending stopped on the site's side (no answer, unexpected page): retried later. */
    BLOCKED,
}

data class PendingDebitsUiState(
    val items: List<PendingDebitItem> = emptyList(),
    val status: PendingDebitsStatus = PendingDebitsStatus.EMPTY,
    val isLoading: Boolean = true,
    val isRetrying: Boolean = false,
)

/** Debit reports stored on the device until they can be sent; the user can drop one or send a refused one again. */
@HiltViewModel
class PendingDebitsViewModel @Inject constructor(
    private val debitSubmissionRepository: DebitSubmissionRepository,
    private val canyonRepository: CanyonRepository,
    connectivityObserver: ConnectivityObserver,
    authRepository: AuthRepository,
) : ViewModel() {

    private val isRetrying = MutableStateFlow(false)

    private val items = debitSubmissionRepository.observePending().map { pending ->
        val names = suspendRunCatching {
            canyonRepository.getCanyonSummaries(pending.map { it.submission.canyonId }.toSet()).associate { it.id to it.nom }
        }.getOrDefault(emptyMap())
        pending.map { it.toItem(names[it.submission.canyonId]) }
    }

    val uiState: StateFlow<PendingDebitsUiState> = combine(
        items,
        connectivityObserver.observe(),
        authRepository.authState.map { it is AuthState.Connected },
        debitSubmissionRepository.observeLastSyncFailure(),
        isRetrying,
    ) { items, online, loggedIn, lastFailure, retrying ->
        PendingDebitsUiState(
            items = items,
            status = pendingDebitsStatus(items, online, loggedIn, lastFailure),
            isLoading = false,
            isRetrying = retrying,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PendingDebitsUiState())

    fun delete(id: Long) {
        viewModelScope.launch { debitSubmissionRepository.deletePending(id) }
    }

    /** Sends the queue now instead of waiting for the next automatic attempt. */
    fun retryNow() {
        if (isRetrying.value) return
        isRetrying.value = true
        viewModelScope.launch {
            try {
                suspendRunCatching { debitSubmissionRepository.syncPending() }
            } finally {
                isRetrying.value = false
            }
        }
    }

    /** Puts a report refused by the site back in the queue and sends it. */
    fun resend(id: Long) {
        viewModelScope.launch {
            debitSubmissionRepository.retryPending(id)
            retryNow()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

internal fun pendingDebitsStatus(
    items: List<PendingDebitItem>,
    online: Boolean,
    loggedIn: Boolean,
    lastFailure: AppError? = null,
): PendingDebitsStatus {
    val toSend = items.filterNot { it.isRejected }
    return when {
        toSend.isEmpty() -> PendingDebitsStatus.EMPTY
        !online -> PendingDebitsStatus.OFFLINE
        !loggedIn && toSend.any { it.needsLogin } -> PendingDebitsStatus.LOGIN_REQUIRED
        lastFailure != null && lastFailure != AppError.Offline && lastFailure.isTransient() -> PendingDebitsStatus.BLOCKED
        else -> PendingDebitsStatus.SENDING
    }
}

private fun PendingDebitSubmission.toItem(canyonName: String?) = PendingDebitItem(
    id = id,
    canyonId = submission.canyonId,
    canyonName = canyonName,
    observationDate = submission.observationDate,
    observationType = submission.observationType,
    debitLevel = submission.debitLevel,
    comment = submission.comment,
    needsLogin = needsLogin,
    rejection = rejection,
)
