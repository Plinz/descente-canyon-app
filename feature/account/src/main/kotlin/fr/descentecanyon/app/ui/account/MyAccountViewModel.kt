package fr.descentecanyon.app.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.error.requiresLogin
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.error.toAppError
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.model.ContributorIdentity
import fr.descentecanyon.app.domain.model.ForumUser
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.LogbookStats
import fr.descentecanyon.app.domain.model.NotificationCenterState
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.agenda
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.domain.repository.CanyonNotesRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.domain.repository.ForumUserRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.domain.repository.NotificationCenterRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.PersonalDataRepository
import fr.descentecanyon.app.domain.repository.PreferencesRepository
import fr.descentecanyon.app.domain.usecase.ImportSiteLogbookUseCase
import fr.descentecanyon.app.ui.common.UiText
import fr.descentecanyon.app.ui.common.toUiText
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A memo shown in the summary: its canyon and its first words. */
data class MemoPreview(val canyonId: Int, val canyonName: String, val text: String)

/** How many things the notifications follow. */
data class FollowCounts(val canyons: Int = 0, val forumCategories: Int = 0, val forumThreads: Int = 0, val members: Int = 0) {
    val total: Int get() = canyons + forumCategories + forumThreads + members
}

data class MyAccountUiState(
    val authState: AuthState = AuthState.Disconnected,
    /** The connected account as the app's member list knows it (counters of the site). */
    val member: ForumUser? = null,
    /** Name and e-mail kept for reports sent without an account. */
    val contributorIdentity: ContributorIdentity? = null,
    /** The outing under way first, then those to come. */
    val outings: List<PlannedOuting> = emptyList(),
    val stats: LogbookStats = LogbookStats(0, 0, 0),
    val recentEntries: List<LogbookEntry> = emptyList(),
    val memoCount: Int = 0,
    val recentMemos: List<MemoPreview> = emptyList(),
    val pendingDebitCount: Int = 0,
    val follows: FollowCounts = FollowCounts(),
    val isImportingFromSite: Boolean = false,
    /** Set when an action needs a login first (import from the site with an expired session). */
    val loginRequest: UiText? = null,
    val message: UiText? = null,
)

/**
 * The user space: the account of the site, the identity of reports sent without one, the outing
 * under way, the logbook and memos, the contributions, the follows and the user's data as files.
 */
@HiltViewModel
class MyAccountViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val forumUserRepository: ForumUserRepository,
    private val preferencesRepository: PreferencesRepository,
    private val logbookRepository: LogbookRepository,
    private val canyonNotesRepository: CanyonNotesRepository,
    private val canyonRepository: CanyonRepository,
    private val outingRepository: OutingRepository,
    private val debitSubmissionRepository: DebitSubmissionRepository,
    private val notificationCenterRepository: NotificationCenterRepository,
    private val personalDataRepository: PersonalDataRepository,
    private val importSiteLogbookUseCase: ImportSiteLogbookUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MyAccountUiState())
    val uiState: StateFlow<MyAccountUiState> = _uiState.asStateFlow()

    init {
        observeAccount()
        observeLogbook()
        observeMemos()
        viewModelScope.launch {
            outingRepository.observeAll().agenda(clock).collect { agenda ->
                _uiState.update { it.copy(outings = listOfNotNull(agenda.underway) + agenda.upcoming) }
            }
        }
        viewModelScope.launch {
            debitSubmissionRepository.observePendingCount().collect { count -> _uiState.update { it.copy(pendingDebitCount = count) } }
        }
        viewModelScope.launch {
            notificationCenterRepository.observeState().map(::followCounts).distinctUntilChanged().collect { counts ->
                _uiState.update { it.copy(follows = counts) }
            }
        }
        viewModelScope.launch {
            val identity = suspendRunCatching { preferencesRepository.getContributorIdentity() }.getOrNull()
            _uiState.update { it.copy(contributorIdentity = identity) }
        }
    }

    /** Brings the descents of "Ma liste de courses" into the logbook. */
    fun importFromSite() {
        if (_uiState.value.isImportingFromSite) return
        _uiState.update { it.copy(isImportingFromSite = true, message = null) }
        viewModelScope.launch {
            importSiteLogbookUseCase().fold(
                onSuccess = { result ->
                    val message = when {
                        result.descents == 0 -> UiText.res(R.string.account_import_site_none)
                        result.added == 0 -> UiText.res(R.string.account_import_site_up_to_date)
                        else -> UiText.plural(R.plurals.account_import_site_done, result.added, result.added)
                    }
                    _uiState.update { it.copy(isImportingFromSite = false, message = message) }
                },
                onFailure = { throwable ->
                    val error = throwable.toAppError()
                    _uiState.update {
                        it.copy(
                            isImportingFromSite = false,
                            loginRequest = UiText.res(R.string.account_import_site_login).takeIf { error.requiresLogin() },
                            message = error.toUiText(fallback = R.string.account_import_site_failed).takeUnless { error.requiresLogin() },
                        )
                    }
                },
            )
        }
    }

    fun saveContributorIdentity(name: String, email: String) {
        val identity = ContributorIdentity(name.trim(), email.trim().ifEmpty { null })
        viewModelScope.launch {
            if (identity.name.isEmpty()) {
                preferencesRepository.clearContributorIdentity()
            } else {
                preferencesRepository.setContributorIdentity(
                    identity,
                )
            }
            _uiState.update { it.copy(contributorIdentity = identity.takeIf { id -> id.name.isNotEmpty() }) }
        }
    }

    fun clearContributorIdentity() {
        viewModelScope.launch {
            preferencesRepository.clearContributorIdentity()
            _uiState.update { it.copy(contributorIdentity = null, message = UiText.res(R.string.account_identity_cleared)) }
        }
    }

    suspend fun exportJson(): String = personalDataRepository.exportJson()

    suspend fun exportLogbookCsv(): String = personalDataRepository.exportLogbookCsv()

    /** Reads a file exported by the app (on this phone or another one). */
    fun importFile(content: String) {
        viewModelScope.launch {
            val message = personalDataRepository.importJson(content).fold(
                onSuccess = { result ->
                    UiText.res(R.string.account_data_import_done, result.outings, result.memos, result.favorites)
                },
                onFailure = { UiText.res(R.string.account_data_import_failed) },
            )
            _uiState.update { it.copy(message = message) }
        }
    }

    fun onExportWritten(success: Boolean) {
        _uiState.update {
            it.copy(message = UiText.res(if (success) R.string.account_data_export_done else R.string.account_data_export_failed))
        }
    }

    fun clearLogbookAndMemos() {
        viewModelScope.launch {
            personalDataRepository.clearLogbookAndMemos()
            _uiState.update { it.copy(message = UiText.res(R.string.account_data_cleared)) }
        }
    }

    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }

    fun onLoginRequestHandled() {
        _uiState.update { it.copy(loginRequest = null) }
    }

    private fun observeAccount() {
        viewModelScope.launch {
            authRepository.authState.collect { state ->
                val username = when (state) {
                    is AuthState.Connected -> state.username
                    is AuthState.SessionExpired -> state.username
                    else -> null
                }
                val member = username?.let { name -> suspendRunCatching { forumUserRepository.getByUsername(name) }.getOrNull() }
                _uiState.update { it.copy(authState = state, member = member) }
            }
        }
    }

    private fun observeLogbook() {
        viewModelScope.launch {
            logbookRepository.observeAll().collect { entries ->
                _uiState.update {
                    it.copy(stats = LogbookStats.of(entries, LocalDate.now(clock)), recentEntries = entries.take(RECENT_ENTRIES))
                }
            }
        }
    }

    private fun observeMemos() {
        viewModelScope.launch {
            canyonNotesRepository.observeAll().collect { notes ->
                val shown = notes.take(RECENT_MEMOS)
                val names = suspendRunCatching { canyonRepository.getCanyonSummaries(shown.map { it.canyonId }.toSet()) }
                    .getOrDefault(emptyList())
                    .associate { it.id to it.nom }
                val previews = shown.mapNotNull { note -> names[note.canyonId]?.let { MemoPreview(note.canyonId, it, note.text) } }
                _uiState.update { it.copy(memoCount = notes.size, recentMemos = previews) }
            }
        }
    }

    private fun followCounts(state: NotificationCenterState) = FollowCounts(
        canyons = state.followedCanyons.size,
        forumCategories = state.followedForumCategories.size,
        forumThreads = state.followedForumThreads.size,
        members = state.followedUsers.size,
    )

    private companion object {
        const val RECENT_ENTRIES = 3
        const val RECENT_MEMOS = 2
    }
}
