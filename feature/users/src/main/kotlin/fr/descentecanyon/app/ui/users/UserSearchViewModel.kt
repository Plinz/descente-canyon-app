package fr.descentecanyon.app.ui.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.model.ForumUser
import fr.descentecanyon.app.domain.repository.ForumUserRepository
import fr.descentecanyon.app.domain.repository.NotificationCenterRepository
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@HiltViewModel
class UserSearchViewModel @Inject constructor(
    private val forumUserRepository: ForumUserRepository,
    private val notificationCenterRepository: NotificationCenterRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    val uiState: StateFlow<UserSearchUiState> = combine(
        query.debounce(200).flatMapLatest { value ->
            if (value.trim().length < 2) {
                flowOf(emptyList())
            } else {
                kotlinx.coroutines.flow.flow { emit(forumUserRepository.search(value)) }
            }
        },
        notificationCenterRepository.observeState(),
    ) { users, notificationState ->
        UserSearchUiState(
            query = query.value,
            users = users,
            followedUsernames = notificationState.followedUsers.map { it.normalizedUsername }.toSet(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSearchUiState())

    fun updateQuery(value: String) {
        query.value = value
    }

    fun toggleFollow(user: ForumUser) {
        viewModelScope.launch { notificationCenterRepository.toggleUserFollow(user) }
    }
}

data class UserSearchUiState(
    val query: String = "",
    val users: List<ForumUser> = emptyList(),
    val followedUsernames: Set<String> = emptySet(),
)
