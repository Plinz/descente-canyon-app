package fr.descentecanyon.app.ui.pending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.model.PendingDebitSubmission
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import java.time.LocalDate
import javax.inject.Inject
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
)

/** What keeps the queue from being sent right now. */
enum class PendingDebitsStatus {
    EMPTY,
    OFFLINE,
    LOGIN_REQUIRED,
    SENDING,
}

data class PendingDebitsUiState(
    val items: List<PendingDebitItem> = emptyList(),
    val status: PendingDebitsStatus = PendingDebitsStatus.EMPTY,
    val isLoading: Boolean = true,
)

/** Debit reports stored on the device until they can be sent; the user can drop one. */
@HiltViewModel
class PendingDebitsViewModel @Inject constructor(
    private val debitSubmissionRepository: DebitSubmissionRepository,
    private val canyonRepository: CanyonRepository,
    connectivityObserver: ConnectivityObserver,
    authRepository: AuthRepository,
) : ViewModel() {

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
    ) { items, online, loggedIn ->
        PendingDebitsUiState(items = items, status = pendingDebitsStatus(items, online, loggedIn), isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PendingDebitsUiState())

    fun delete(id: Long) {
        viewModelScope.launch { debitSubmissionRepository.deletePending(id) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

internal fun pendingDebitsStatus(items: List<PendingDebitItem>, online: Boolean, loggedIn: Boolean): PendingDebitsStatus = when {
    items.isEmpty() -> PendingDebitsStatus.EMPTY
    !online -> PendingDebitsStatus.OFFLINE
    !loggedIn && items.any { it.needsLogin } -> PendingDebitsStatus.LOGIN_REQUIRED
    else -> PendingDebitsStatus.SENDING
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
)
