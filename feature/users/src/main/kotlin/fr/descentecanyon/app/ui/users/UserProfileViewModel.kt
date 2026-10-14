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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class UserProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val forumUserRepository: ForumUserRepository,
    private val notificationCenterRepository: NotificationCenterRepository,
) : ViewModel() {
    private val normalizedUsername: String = checkNotNull(savedStateHandle["normalizedUsername"])
    private val _uiState = MutableStateFlow(UserProfileUiState())
    val uiState: StateFlow<UserProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val user = forumUserRepository.getByNormalizedUsername(normalizedUsername)
            val followed = notificationCenterRepository.observeIsUserFollowed(normalizedUsername).first()
            _uiState.value = UserProfileUiState(user = user, isFollowed = followed)
            notificationCenterRepository.observeIsUserFollowed(normalizedUsername).collect {
                _uiState.value = _uiState.value.copy(isFollowed = it)
            }
        }
    }

    fun toggleFollow() {
        val user = _uiState.value.user ?: return
        viewModelScope.launch { notificationCenterRepository.toggleUserFollow(user) }
    }
}

data class UserProfileUiState(val user: ForumUser? = null, val isFollowed: Boolean = false)
