package fr.descentecanyon.app.ui.users

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.model.ForumUser
import fr.descentecanyon.app.domain.repository.ForumUserRepository
import fr.descentecanyon.app.domain.repository.NotificationCenterRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class UserProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val forumUserRepository: ForumUserRepository,
    private val notificationCenterRepository: NotificationCenterRepository,
) : ViewModel() {
    private val username: String = checkNotNull(savedStateHandle["username"])
    private val _uiState = MutableStateFlow(UserProfileUiState(username = username))
    val uiState: StateFlow<UserProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val user = forumUserRepository.getByUsername(username)
            _uiState.update { it.copy(user = user, isLoaded = true) }
        }
        viewModelScope.launch {
            notificationCenterRepository.observeIsUserFollowed(username).collect { followed ->
                _uiState.update { it.copy(isFollowed = followed) }
            }
        }
    }

    fun toggleFollow() {
        val user = _uiState.value.user ?: return
        viewModelScope.launch { notificationCenterRepository.toggleUserFollow(user) }
    }
}

data class UserProfileUiState(
    val username: String,
    val user: ForumUser? = null,
    /** Whether the catalog was read: until then, a missing [user] is not "not found". */
    val isLoaded: Boolean = false,
    val isFollowed: Boolean = false,
)
