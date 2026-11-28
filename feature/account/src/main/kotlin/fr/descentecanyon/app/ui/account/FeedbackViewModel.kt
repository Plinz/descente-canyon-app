package fr.descentecanyon.app.ui.account

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.FeedbackContact
import fr.descentecanyon.app.domain.model.FeedbackDraft
import fr.descentecanyon.app.domain.model.FeedbackKind
import fr.descentecanyon.app.domain.model.FeedbackLimits
import fr.descentecanyon.app.domain.model.FeedbackMessage
import fr.descentecanyon.app.domain.model.FeedbackSendResult
import fr.descentecanyon.app.domain.model.FeedbackTechnicalInfo
import fr.descentecanyon.app.domain.repository.FeedbackRepository
import fr.descentecanyon.app.ui.common.UiText
import fr.descentecanyon.app.ui.common.toUiText
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeedbackUiState(
    val kind: FeedbackKind? = null,
    val text: String = "",
    /** Joined by default: what helps most to reproduce a bug. */
    val attachTechnical: Boolean = true,
    val technical: FeedbackTechnicalInfo? = null,
    /** The descente-canyon.com account the app is logged in with; null when logged out (sent anonymously). */
    val contact: FeedbackContact? = null,
    val isLoadingContact: Boolean = true,
    /** Joined by default when logged in, so that the answer can come by e-mail too. */
    val attachContact: Boolean = true,
    /** The server takes messages now. */
    val isAvailable: Boolean = false,
    val isSending: Boolean = false,
    /** This installation's messages, newest first, with what became of them. */
    val messages: List<FeedbackMessage> = emptyList(),
    val message: UiText? = null,
) {
    val length: Int get() = FeedbackLimits.length(text)

    val canSend: Boolean get() = isAvailable && !isSending && kind != null && FeedbackLimits.isSendable(text)
}

/** The idea box: a bug, an idea or a change, sent to the app's server, and the answers to earlier ones. */
@HiltViewModel
class FeedbackViewModel @Inject constructor(private val savedStateHandle: SavedStateHandle, private val repository: FeedbackRepository) :
    ViewModel() {

    private val _uiState = MutableStateFlow(
        FeedbackUiState(
            kind = savedStateHandle.get<String>(KEY_KIND)?.let { name -> FeedbackKind.entries.firstOrNull { it.name == name } },
            text = savedStateHandle.get<String>(KEY_TEXT).orEmpty(),
            attachTechnical = savedStateHandle.get<Boolean>(KEY_TECHNICAL) ?: true,
            attachContact = savedStateHandle.get<Boolean>(KEY_CONTACT) ?: true,
            technical = repository.technicalInfo(),
        ),
    )
    val uiState: StateFlow<FeedbackUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAvailable().collect { available -> _uiState.update { it.copy(isAvailable = available) } }
        }
        viewModelScope.launch {
            repository.observeMine().collect { messages -> _uiState.update { it.copy(messages = messages) } }
        }
        viewModelScope.launch {
            val contact = repository.contact()
            _uiState.update { it.copy(contact = contact, isLoadingContact = false) }
        }
        viewModelScope.launch {
            repository.refreshMine()
            // The answers are on screen now: no longer new in the user space.
            repository.markRepliesSeen()
        }
    }

    fun selectKind(kind: FeedbackKind) {
        savedStateHandle[KEY_KIND] = kind.name
        _uiState.update { it.copy(kind = kind) }
    }

    fun onTextChanged(text: String) {
        val bounded = text.take(FeedbackLimits.TEXT_MAX + TEXT_SLACK)
        savedStateHandle[KEY_TEXT] = bounded
        _uiState.update { it.copy(text = bounded) }
    }

    fun setAttachTechnical(attach: Boolean) {
        savedStateHandle[KEY_TECHNICAL] = attach
        _uiState.update { it.copy(attachTechnical = attach) }
    }

    fun setAttachContact(attach: Boolean) {
        savedStateHandle[KEY_CONTACT] = attach
        _uiState.update { it.copy(attachContact = attach) }
    }

    fun send() {
        val state = _uiState.value
        val kind = state.kind ?: return
        if (!state.canSend) return
        _uiState.update { it.copy(isSending = true, message = null) }
        val draft = FeedbackDraft(
            kind = kind,
            text = state.text,
            technical = state.technical.takeIf { state.attachTechnical },
            contact = state.contact.takeIf { state.attachContact },
        )
        viewModelScope.launch {
            repository.send(draft).fold(
                onSuccess = { result ->
                    savedStateHandle[KEY_TEXT] = ""
                    savedStateHandle.remove<String>(KEY_KIND)
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            text = "",
                            kind = null,
                            message = UiText.res(
                                if (result == FeedbackSendResult.SENT) R.string.feedback_sent else R.string.feedback_queued,
                            ),
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isSending = false, message = error.toUiText(fallback = R.string.feedback_send_failed)) }
                },
            )
        }
    }

    fun delete(message: FeedbackMessage) {
        viewModelScope.launch {
            repository.delete(message).onFailure { error ->
                _uiState.update { it.copy(message = error.toUiText(fallback = R.string.feedback_delete_failed)) }
            }
        }
    }

    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }

    private companion object {
        const val KEY_KIND = "feedback.kind"
        const val KEY_TEXT = "feedback.text"
        const val KEY_TECHNICAL = "feedback.technical"
        const val KEY_CONTACT = "feedback.contact"

        /** A paste a little over the limit stays visible, with the counter in red, rather than cut silently. */
        const val TEXT_SLACK = 200
    }
}
