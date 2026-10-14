package fr.descentecanyon.app.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.model.FollowedCanyon
import fr.descentecanyon.app.domain.model.FollowedForumCategory
import fr.descentecanyon.app.domain.model.FollowedForumThread
import fr.descentecanyon.app.domain.model.FollowedUser
import fr.descentecanyon.app.domain.model.TrackedActivityEvent
import fr.descentecanyon.app.domain.repository.NotificationCenterRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NotificationCenterUiState(
    val followedCanyons: List<FollowedCanyon> = emptyList(),
    val followedForumCategories: List<FollowedForumCategory> = emptyList(),
    val followedForumThreads: List<FollowedForumThread> = emptyList(),
    val followedUsers: List<FollowedUser> = emptyList(),
    val recentEvents: List<TrackedActivityEvent> = emptyList(),
)

@HiltViewModel
class NotificationCenterViewModel @Inject constructor(private val notificationCenterRepository: NotificationCenterRepository) :
    ViewModel() {

    private val _uiState = MutableStateFlow(NotificationCenterUiState())
    val uiState: StateFlow<NotificationCenterUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            notificationCenterRepository.observeState().collect { state ->
                _uiState.update {
                    it.copy(
                        followedCanyons = state.followedCanyons,
                        followedForumCategories = state.followedForumCategories,
                        followedForumThreads = state.followedForumThreads,
                        followedUsers = state.followedUsers,
                        recentEvents = state.recentEvents,
                    )
                }
            }
        }
    }

    fun removeCanyonFollow(canyonId: Int) {
        viewModelScope.launch {
            notificationCenterRepository.removeCanyonFollow(canyonId)
        }
    }

    fun removeForumCategoryFollow(key: String) {
        viewModelScope.launch {
            notificationCenterRepository.removeForumCategoryFollow(key)
        }
    }

    fun removeForumThreadFollow(topicId: Int) {
        viewModelScope.launch {
            notificationCenterRepository.removeForumThreadFollow(topicId)
        }
    }

    fun removeUserFollow(normalizedUsername: String) {
        viewModelScope.launch {
            notificationCenterRepository.removeUserFollow(normalizedUsername)
        }
    }

    fun clearRecentActivity() {
        viewModelScope.launch {
            notificationCenterRepository.clearRecentActivity()
        }
    }
}
