package fr.descentecanyon.app.ui.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.model.FollowedUser
import fr.descentecanyon.app.domain.model.ForumUser
import fr.descentecanyon.app.domain.repository.ForumUserRepository
import fr.descentecanyon.app.domain.repository.NotificationCenterRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class UserSearchViewModel @Inject constructor(
    private val forumUserRepository: ForumUserRepository,
    private val notificationCenterRepository: NotificationCenterRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    private val _uiState = MutableStateFlow(UserSearchUiState())
    val uiState: StateFlow<UserSearchUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            query.debounce(SEARCH_DEBOUNCE_MS)
                .mapLatest { value -> value to if (value.trim().length < 2) emptyList() else forumUserRepository.search(value) }
                .collect { (searched, users) -> _uiState.update { it.copy(users = users, searchedQuery = searched) } }
        }
        viewModelScope.launch {
            notificationCenterRepository.observeState().collect { state ->
                _uiState.update { it.copy(followedUsers = state.followedUsers) }
            }
        }
    }

    /**
     * The text typed goes to the state at once, only the search waits for a pause: a text field fed
     * by the debounced results lost every letter typed in between.
     */
    fun updateQuery(value: String) {
        query.value = value
        _uiState.update { it.copy(query = value) }
    }

    fun toggleFollow(user: ForumUser) {
        viewModelScope.launch { notificationCenterRepository.toggleUserFollow(user) }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 200L
    }
}

data class UserSearchUiState(
    val query: String = "",
    val users: List<ForumUser> = emptyList(),
    /** The text [users] were found for: the search of the text typed comes a moment later. */
    val searchedQuery: String = "",
    val followedUsers: List<FollowedUser> = emptyList(),
) {
    val isSearching: Boolean get() = query != searchedQuery

    fun isFollowed(user: ForumUser): Boolean = followedUsers.any { it.hasUsername(user.username) }
}
