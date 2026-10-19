package fr.descentecanyon.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.ForumActiveTopic
import fr.descentecanyon.app.domain.model.ForumCategory
import fr.descentecanyon.app.domain.model.HomeFeedType
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.ReportZone
import fr.descentecanyon.app.domain.model.ZoneCountry
import fr.descentecanyon.app.domain.model.buildZoneTree
import fr.descentecanyon.app.domain.model.forumCategoryKey
import fr.descentecanyon.app.domain.model.subdivisionsFor
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.domain.repository.ForumRepository
import fr.descentecanyon.app.domain.repository.NotificationCenterRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.PreferencesRepository
import fr.descentecanyon.app.domain.usecase.EndOutingUseCase
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HomeFeedNotice {
    OFFLINE_BANNER,
    OFFLINE_EMPTY,
    STALE_BANNER,
    SERVICE_UNAVAILABLE,
}

data class HomeFeedSectionState<T>(
    val items: List<T> = emptyList(),
    val isLoading: Boolean = false,
    val notice: HomeFeedNotice? = null,
    val lastSyncedAtEpochMs: Long? = null,
)

data class HomeDebitGeoFilterState(
    val selectedCountry: String? = null,
    val selectedDepartment: String? = null,
    /** The countries of the latest reports, each with its subdivisions, in alphabetical order. */
    val countries: List<ZoneCountry> = emptyList(),
    val totalCount: Int = 0,
    val displayedCount: Int = 0,
    val filteredCount: Int = 0,
    val canLoadMore: Boolean = false,
) {
    fun hasActiveFilter(): Boolean = selectedCountry != null || selectedDepartment != null
}

data class HomeForumFilterState(
    val selectedCategoryKey: String? = null,
    /** The forums in the order of the site's forum index. */
    val availableCategories: List<ForumCategory> = emptyList(),
    /** How many of the active topics each forum holds, by category key. */
    val topicCounts: Map<String, Int> = emptyMap(),
    val totalTopicCount: Int = 0,
) {
    fun hasActiveFilter(): Boolean = selectedCategoryKey != null

    fun selectedCategory(): ForumCategory? = selectedCategoryKey?.let { key -> availableCategories.firstOrNull { it.key() == key } }
}

internal fun ForumCategory.key(): String = forumCategoryKey(forumId, forumName)

data class HomeUiState(
    val selectedFeed: HomeFeedType = HomeFeedType.DEBITS,
    val isOnline: Boolean = true,
    val debitFeed: HomeFeedSectionState<Debit> = HomeFeedSectionState(),
    val forumFeed: HomeFeedSectionState<ForumActiveTopic> = HomeFeedSectionState(),
    val debitGeoFilter: HomeDebitGeoFilterState = HomeDebitGeoFilterState(),
    val forumFilter: HomeForumFilterState = HomeForumFilterState(),
    val isLocalCanyonCatalogLoaded: Boolean = false,
    val localCanyonIds: Set<Int> = emptySet(),
    /** Department and country of each catalog canyon, shown on the reports. */
    val canyonPlaces: Map<Int, String> = emptyMap(),
    val followedForumCategoryKeys: Set<String> = emptySet(),
    val followedForumThreadIds: Set<Int> = emptySet(),
    /** Debit reports written offline and not sent yet. */
    val pendingDebitCount: Int = 0,
    /** The outing the user announced, until they are back. */
    val activeOuting: PlannedOuting? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val debitRepository: DebitRepository,
    private val forumRepository: ForumRepository,
    private val canyonRepository: CanyonRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val preferencesRepository: PreferencesRepository,
    private val notificationCenterRepository: NotificationCenterRepository,
    private val debitSubmissionRepository: DebitSubmissionRepository,
    private val outingRepository: OutingRepository,
    private val endOutingUseCase: EndOutingUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var latestDebitsJob: Job? = null
    private var activeTopicsJob: Job? = null
    private var previousOnline: Boolean? = null
    private var hasUserSelectedFeed = false
    private var hasUserSelectedZone = false
    private var allLatestDebits: List<Debit> = emptyList()
    private var searchCatalog: List<CanyonSearchItem> = emptyList()
    private var visibleDebitLimit = LATEST_DEBITS_PAGE_SIZE
    private var allForumTopics: List<ForumActiveTopic> = emptyList()

    init {
        observeConnectivity()
        observeSearchCatalog()
        observeTrackedForumCategories()
        observePendingDebits()
        observeActiveOuting()
        restoreHomeState()
    }

    private fun observeActiveOuting() {
        viewModelScope.launch {
            outingRepository.observeCurrent().collect { outing -> _uiState.update { it.copy(activeOuting = outing) } }
        }
    }

    /** "I'm back": the outing goes to the logbook and its notifications are cancelled. */
    fun endOuting() {
        val outing = uiState.value.activeOuting ?: return
        viewModelScope.launch { endOutingUseCase(outing.canyonId) }
    }

    private fun observePendingDebits() {
        viewModelScope.launch {
            debitSubmissionRepository.observePendingCount().collect { count ->
                _uiState.update { it.copy(pendingDebitCount = count) }
            }
        }
    }

    fun toggleForumCategoryFollow(category: ForumCategory) {
        viewModelScope.launch {
            notificationCenterRepository.toggleForumCategoryFollow(
                forumId = category.forumId,
                forumName = category.forumName,
                baselineTopics = allForumTopics,
            )
        }
    }

    fun toggleForumThreadFollow(topic: ForumActiveTopic) {
        viewModelScope.launch {
            notificationCenterRepository.toggleForumThreadFollow(
                topic = topic,
                baselineTopics = allForumTopics,
            )
        }
    }

    fun selectForumCategory(categoryKey: String?) {
        _uiState.update { state ->
            state.copy(
                forumFilter = state.forumFilter.copy(selectedCategoryKey = categoryKey?.takeIf { it.isNotBlank() }),
            ).withForumPresentation()
        }
    }

    fun clearForumCategoryFilter() {
        _uiState.update { state ->
            state.copy(
                forumFilter = state.forumFilter.copy(selectedCategoryKey = null),
            ).withForumPresentation()
        }
    }

    fun selectFeed(type: HomeFeedType) {
        hasUserSelectedFeed = true
        if (_uiState.value.selectedFeed == type) {
            if (uiState.value.isOnline) {
                refreshFeed(type)
            }
            return
        }

        _uiState.update { state -> state.copy(selectedFeed = type) }
        viewModelScope.launch {
            preferencesRepository.setSelectedHomeFeed(type)
        }

        if (uiState.value.isOnline) {
            refreshFeed(type)
        }
    }

    fun refreshSelectedFeed() {
        refreshFeed(uiState.value.selectedFeed)
    }

    /** A [subdivision] is only kept with its [country]. */
    fun selectDebitZone(country: String?, subdivision: String? = null) {
        visibleDebitLimit = LATEST_DEBITS_PAGE_SIZE
        val selectedCountry = country?.takeIf { it.isNotBlank() }
        _uiState.update { state ->
            state.copy(
                debitGeoFilter = state.debitGeoFilter.copy(
                    selectedCountry = selectedCountry,
                    selectedDepartment = subdivision?.takeIf { it.isNotBlank() && selectedCountry != null },
                ),
            ).withDebitPresentation()
        }
        saveReportZone()
    }

    fun clearDebitGeoFilter() {
        visibleDebitLimit = LATEST_DEBITS_PAGE_SIZE
        _uiState.update { state ->
            state.copy(
                debitGeoFilter = state.debitGeoFilter.copy(
                    selectedCountry = null,
                    selectedDepartment = null,
                ),
            ).withDebitPresentation()
        }
        saveReportZone()
    }

    /** The area is kept: most users look at the reports of the same department every time. */
    private fun saveReportZone() {
        hasUserSelectedZone = true
        val filter = uiState.value.debitGeoFilter
        val zone = filter.selectedCountry?.let { ReportZone(country = it, department = filter.selectedDepartment) }
        viewModelScope.launch { preferencesRepository.setLatestReportsZone(zone) }
    }

    fun loadMoreDebits() {
        if (!uiState.value.debitGeoFilter.canLoadMore) return
        visibleDebitLimit += LATEST_DEBITS_PAGE_SIZE
        _uiState.update { state -> state.withDebitPresentation() }
    }

    private fun restoreHomeState() {
        viewModelScope.launch {
            val restoredSelectedFeed = preferencesRepository.getSelectedHomeFeed() ?: HomeFeedType.DEBITS
            val restoredZone = preferencesRepository.getLatestReportsZone()
            val cachedDebits = debitRepository.getCachedLatestDebits(LATEST_DEBITS_CACHE_LIMIT)
            val cachedTopics = forumRepository.getCachedActiveTopics(ACTIVE_TOPICS_LIMIT)
            val cachedForumCategories = forumRepository.getCachedCategories()
            allLatestDebits = cachedDebits.items
            allForumTopics = cachedTopics.items
            visibleDebitLimit = LATEST_DEBITS_PAGE_SIZE

            _uiState.update { state ->
                state.copy(
                    selectedFeed = if (hasUserSelectedFeed) state.selectedFeed else restoredSelectedFeed,
                    debitFeed = state.debitFeed.copy(
                        items = cachedDebits.items,
                        lastSyncedAtEpochMs = cachedDebits.syncedAtEpochMs,
                        notice = resolvePassiveNotice(
                            items = cachedDebits.items,
                            isOnline = state.isOnline,
                            currentNotice = null,
                        ),
                    ),
                    forumFeed = state.forumFeed.copy(
                        items = cachedTopics.items,
                        lastSyncedAtEpochMs = cachedTopics.syncedAtEpochMs,
                        notice = resolvePassiveNotice(
                            items = cachedTopics.items,
                            isOnline = state.isOnline,
                            currentNotice = null,
                        ),
                    ),
                    forumFilter = state.forumFilter.copy(
                        availableCategories = cachedForumCategories.items,
                    ),
                    debitGeoFilter = if (hasUserSelectedZone || restoredZone == null) {
                        state.debitGeoFilter
                    } else {
                        state.debitGeoFilter.copy(selectedCountry = restoredZone.country, selectedDepartment = restoredZone.department)
                    },
                ).withDebitPresentation().withForumPresentation()
            }

            if (connectivityObserver.isCurrentlyOnline()) {
                val selectedFeed = uiState.value.selectedFeed
                refreshFeed(selectedFeed)
                refreshFeed(otherFeedOf(selectedFeed), backgroundOnly = true)
            }
        }
    }

    private fun observeSearchCatalog() {
        viewModelScope.launch {
            canyonRepository.observeSearchCatalog().collect { catalog ->
                searchCatalog = catalog
                _uiState.update { state ->
                    state.copy(
                        isLocalCanyonCatalogLoaded = catalog.isNotEmpty(),
                        localCanyonIds = catalog.mapTo(mutableSetOf()) { it.id },
                        canyonPlaces = catalog.associate { item ->
                            item.id to listOfNotNull(item.departement, item.pays.takeIf { it.isNotBlank() }).distinct().joinToString(", ")
                        },
                    ).withDebitPresentation()
                }
            }
        }
    }

    private fun observeConnectivity() {
        viewModelScope.launch {
            connectivityObserver.observe().collect { online ->
                val shouldRefresh = previousOnline == false && online
                previousOnline = online

                _uiState.update { state ->
                    state.copy(
                        isOnline = online,
                        debitFeed = state.debitFeed.copy(
                            notice = resolvePassiveNotice(
                                items = state.debitFeed.items,
                                isOnline = online,
                                currentNotice = state.debitFeed.notice,
                            ),
                        ),
                        forumFeed = state.forumFeed.copy(
                            notice = resolvePassiveNotice(
                                items = state.forumFeed.items,
                                isOnline = online,
                                currentNotice = state.forumFeed.notice,
                            ),
                        ),
                    )
                }

                if (shouldRefresh) {
                    val selectedFeed = uiState.value.selectedFeed
                    refreshFeed(selectedFeed)
                    refreshFeed(otherFeedOf(selectedFeed), backgroundOnly = true)
                }
            }
        }
    }

    private fun observeTrackedForumCategories() {
        viewModelScope.launch {
            notificationCenterRepository.observeState().collect { state ->
                _uiState.update {
                    it.copy(
                        followedForumCategoryKeys = state.followedForumCategories.mapTo(mutableSetOf()) { category -> category.key },
                        followedForumThreadIds = state.followedForumThreads.mapTo(mutableSetOf()) { thread -> thread.topicId },
                    ).withForumPresentation()
                }
            }
        }
    }

    private fun refreshFeed(feedType: HomeFeedType, backgroundOnly: Boolean = false) {
        if (!connectivityObserver.isCurrentlyOnline()) {
            _uiState.update { state ->
                when (feedType) {
                    HomeFeedType.DEBITS -> state.copy(
                        isOnline = false,
                        debitFeed = state.debitFeed.copy(
                            isLoading = false,
                            notice = resolveNotice(
                                items = state.debitFeed.items,
                                isOnline = false,
                                failureKind = HomeFeedFailureKind.NETWORK,
                            ),
                        ),
                    )

                    HomeFeedType.FORUM -> state.copy(
                        isOnline = false,
                        forumFeed = state.forumFeed.copy(
                            isLoading = false,
                            notice = resolveNotice(
                                items = state.forumFeed.items,
                                isOnline = false,
                                failureKind = HomeFeedFailureKind.NETWORK,
                            ),
                        ),
                    )
                }
            }
            return
        }

        when (feedType) {
            HomeFeedType.DEBITS -> {
                latestDebitsJob?.cancel()
                latestDebitsJob = viewModelScope.launch {
                    _uiState.update { state ->
                        state.copy(
                            debitFeed = state.debitFeed.copy(
                                isLoading = !backgroundOnly || state.selectedFeed == HomeFeedType.DEBITS,
                            ),
                        )
                    }

                    debitRepository.refreshLatestDebits(LATEST_DEBITS_CACHE_LIMIT).fold(
                        onSuccess = { cached ->
                            allLatestDebits = cached.items
                            visibleDebitLimit = LATEST_DEBITS_PAGE_SIZE
                            _uiState.update { state ->
                                state.copy(
                                    debitFeed = state.debitFeed.copy(
                                        items = cached.items,
                                        isLoading = false,
                                        notice = resolveNotice(
                                            items = cached.items,
                                            isOnline = state.isOnline,
                                            failureKind = null,
                                        ),
                                        lastSyncedAtEpochMs = cached.syncedAtEpochMs,
                                    ),
                                ).withDebitPresentation()
                            }
                        },
                        onFailure = { throwable ->
                            _uiState.update { state ->
                                state.copy(
                                    debitFeed = state.debitFeed.copy(
                                        isLoading = false,
                                        notice = resolveNotice(
                                            items = state.debitFeed.items,
                                            isOnline = state.isOnline,
                                            failureKind = throwable.toFailureKind(state.isOnline),
                                        ),
                                    ),
                                )
                            }
                        },
                    )
                }
            }

            HomeFeedType.FORUM -> {
                activeTopicsJob?.cancel()
                activeTopicsJob = viewModelScope.launch {
                    _uiState.update { state ->
                        state.copy(
                            forumFeed = state.forumFeed.copy(
                                isLoading = !backgroundOnly || state.selectedFeed == HomeFeedType.FORUM,
                            ),
                        )
                    }

                    forumRepository.refreshActiveTopics(ACTIVE_TOPICS_LIMIT).fold(
                        onSuccess = { cached ->
                            allForumTopics = cached.items
                            _uiState.update { state ->
                                state.copy(
                                    forumFeed = state.forumFeed.copy(
                                        items = cached.items,
                                        isLoading = false,
                                        notice = resolveNotice(
                                            items = cached.items,
                                            isOnline = state.isOnline,
                                            failureKind = null,
                                        ),
                                        lastSyncedAtEpochMs = cached.syncedAtEpochMs,
                                    ),
                                ).withForumPresentation()
                            }
                            refreshForumCategoriesInBackground()
                        },
                        onFailure = { throwable ->
                            _uiState.update { state ->
                                state.copy(
                                    forumFeed = state.forumFeed.copy(
                                        isLoading = false,
                                        notice = resolveNotice(
                                            items = state.forumFeed.items,
                                            isOnline = state.isOnline,
                                            failureKind = throwable.toFailureKind(state.isOnline),
                                        ),
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    private fun resolvePassiveNotice(items: List<*>, isOnline: Boolean, currentNotice: HomeFeedNotice?): HomeFeedNotice? = when {
        !isOnline && items.isNotEmpty() -> HomeFeedNotice.OFFLINE_BANNER
        !isOnline && items.isEmpty() -> HomeFeedNotice.OFFLINE_EMPTY
        isOnline && (currentNotice == HomeFeedNotice.OFFLINE_BANNER || currentNotice == HomeFeedNotice.OFFLINE_EMPTY) -> null
        else -> currentNotice
    }

    private fun resolveNotice(items: List<*>, isOnline: Boolean, failureKind: HomeFeedFailureKind?): HomeFeedNotice? = when {
        !isOnline && items.isNotEmpty() -> HomeFeedNotice.OFFLINE_BANNER
        failureKind == HomeFeedFailureKind.NETWORK && items.isNotEmpty() -> HomeFeedNotice.OFFLINE_BANNER
        failureKind == HomeFeedFailureKind.NETWORK && items.isEmpty() -> HomeFeedNotice.OFFLINE_EMPTY
        failureKind == HomeFeedFailureKind.SERVICE && items.isNotEmpty() -> HomeFeedNotice.STALE_BANNER
        failureKind == HomeFeedFailureKind.SERVICE && items.isEmpty() -> HomeFeedNotice.SERVICE_UNAVAILABLE
        else -> null
    }

    private fun Throwable.toFailureKind(isOnline: Boolean): HomeFeedFailureKind = if (!isOnline || isLikelyNetworkIssue()) {
        HomeFeedFailureKind.NETWORK
    } else {
        HomeFeedFailureKind.SERVICE
    }

    private fun Throwable.isLikelyNetworkIssue(): Boolean = generateSequence(this) { it.cause }.any { cause ->
        cause is UnknownHostException ||
            cause is UnresolvedAddressException ||
            cause is ConnectException ||
            cause is SocketTimeoutException ||
            cause.message?.contains("Unable to resolve host", ignoreCase = true) == true ||
            cause.message?.contains("No address associated with hostname", ignoreCase = true) == true
    }

    private fun otherFeedOf(type: HomeFeedType): HomeFeedType = when (type) {
        HomeFeedType.DEBITS -> HomeFeedType.FORUM
        HomeFeedType.FORUM -> HomeFeedType.DEBITS
    }

    private fun refreshForumCategoriesInBackground() {
        viewModelScope.launch {
            forumRepository.refreshCategories().onSuccess { cached ->
                _uiState.update { state ->
                    state.copy(
                        forumFilter = state.forumFilter.copy(
                            availableCategories = cached.items,
                        ),
                    ).withForumPresentation()
                }
            }
        }
    }

    private fun HomeUiState.withDebitPresentation(): HomeUiState {
        val presentation = buildDebitPresentation(
            allDebits = allLatestDebits,
            catalog = searchCatalog,
            filter = debitGeoFilter,
            visibleLimit = visibleDebitLimit,
        )
        return copy(
            debitFeed = debitFeed.copy(items = presentation.visibleItems),
            debitGeoFilter = debitGeoFilter.copy(
                countries = presentation.countries,
                totalCount = allLatestDebits.size,
                displayedCount = presentation.visibleItems.size,
                filteredCount = presentation.filteredCount,
                canLoadMore = presentation.canLoadMore,
            ),
        )
    }

    private fun HomeUiState.withForumPresentation(): HomeUiState {
        val selectedCategoryKey = forumFilter.selectedCategoryKey
        val filteredTopics = if (selectedCategoryKey == null) {
            allForumTopics
        } else {
            allForumTopics.filter { topic -> forumCategoryKey(topic.forumId, topic.forumName) == selectedCategoryKey }
        }
        return copy(
            forumFeed = forumFeed.copy(items = filteredTopics),
            forumFilter = forumFilter.copy(
                topicCounts = allForumTopics.groupingBy { topic -> forumCategoryKey(topic.forumId, topic.forumName) }.eachCount(),
                totalTopicCount = allForumTopics.size,
            ),
        )
    }

    private companion object {
        const val LATEST_DEBITS_PAGE_SIZE = 20
        const val LATEST_DEBITS_CACHE_LIMIT = Int.MAX_VALUE
        const val ACTIVE_TOPICS_LIMIT = 12
    }
}

private enum class HomeFeedFailureKind {
    NETWORK,
    SERVICE,
}

private data class DebitPresentation(
    val visibleItems: List<Debit>,
    val countries: List<ZoneCountry>,
    val filteredCount: Int,
    val canLoadMore: Boolean,
)

private fun buildDebitPresentation(
    allDebits: List<Debit>,
    catalog: List<CanyonSearchItem>,
    filter: HomeDebitGeoFilterState,
    visibleLimit: Int,
): DebitPresentation {
    val debitCanyonIds = allDebits.mapTo(HashSet()) { it.canyonId }
    val reportedCanyons = catalog.filter { it.id in debitCanyonIds }.associateBy { it.id }

    val filteredDebits = if (!filter.hasActiveFilter()) {
        allDebits
    } else {
        val matchingIds = reportedCanyons.values.asSequence()
            .filter { it.matchesCountry(filter.selectedCountry) }
            .filter { it.matchesDepartment(filter.selectedCountry, filter.selectedDepartment) }
            .map { it.id }
            .toSet()
        allDebits.filter { it.canyonId in matchingIds }
    }
    val effectiveLimit = visibleLimit.coerceAtLeast(0)

    return DebitPresentation(
        visibleItems = filteredDebits.take(effectiveLimit),
        // Counted per report: a canyon with three recent reports weighs three.
        countries = buildZoneTree(allDebits.mapNotNull { reportedCanyons[it.canyonId] }),
        filteredCount = filteredDebits.size,
        canLoadMore = filteredDebits.size > effectiveLimit,
    )
}

private fun CanyonSearchItem.matchesCountry(country: String?): Boolean = country == null || countryTokens.any {
    it.equals(country, ignoreCase = true)
}

private fun CanyonSearchItem.matchesDepartment(country: String?, department: String?): Boolean =
    department == null || subdivisionsFor(country).any {
        it.equals(department, ignoreCase = true)
    }
