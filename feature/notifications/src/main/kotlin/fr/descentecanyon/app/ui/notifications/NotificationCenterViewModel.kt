package fr.descentecanyon.app.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.FollowedCanyon
import fr.descentecanyon.app.domain.model.FollowedForumCategory
import fr.descentecanyon.app.domain.model.FollowedForumThread
import fr.descentecanyon.app.domain.model.FollowedUser
import fr.descentecanyon.app.domain.model.TrackedActivityEvent
import fr.descentecanyon.app.domain.repository.CanyonRepository
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
    /** Department and country of the followed canyons, to tell homonyms apart. */
    val canyonPlaces: Map<Int, String> = emptyMap(),
)

@HiltViewModel
class NotificationCenterViewModel @Inject constructor(
    private val notificationCenterRepository: NotificationCenterRepository,
    private val canyonRepository: CanyonRepository,
) : ViewModel() {

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
                loadCanyonPlaces(state.followedCanyons.map { it.canyonId })
            }
        }
    }

    private suspend fun loadCanyonPlaces(canyonIds: List<Int>) {
        val missing = canyonIds.filterNot { it in _uiState.value.canyonPlaces }.toSet()
        if (missing.isEmpty()) return
        val places = suspendRunCatching { canyonRepository.getCanyonSummaries(missing) }.getOrDefault(emptyList())
            .associate { summary ->
                summary.id to
                    listOfNotNull(summary.departement, summary.pays.takeIf { it.isNotBlank() }).joinToString(" · ")
            }
            .filterValues { it.isNotBlank() }
        _uiState.update { it.copy(canyonPlaces = it.canyonPlaces + places) }
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

    fun removeUserFollow(username: String) {
        viewModelScope.launch {
            notificationCenterRepository.removeUserFollow(username)
        }
    }

    fun clearRecentActivity() {
        viewModelScope.launch {
            notificationCenterRepository.clearRecentActivity()
        }
    }
}
