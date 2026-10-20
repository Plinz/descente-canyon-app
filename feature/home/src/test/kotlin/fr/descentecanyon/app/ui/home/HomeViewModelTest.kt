package fr.descentecanyon.app.ui.home

import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.CachedItems
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.Debit
import fr.descentecanyon.app.domain.model.ForumActiveTopic
import fr.descentecanyon.app.domain.model.ForumCategory
import fr.descentecanyon.app.domain.model.HomeFeedType
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.NotificationCenterState
import fr.descentecanyon.app.domain.model.ReportZone
import fr.descentecanyon.app.domain.model.ZoneCountry
import fr.descentecanyon.app.domain.model.ZoneSubdivision
import fr.descentecanyon.app.domain.model.normalizeForSearch
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.domain.repository.ForumRepository
import fr.descentecanyon.app.domain.repository.NotificationCenterRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.usecase.EndOutingUseCase
import fr.descentecanyon.app.testutil.FakePreferencesRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.net.UnknownHostException
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val debitRepository = mockk<DebitRepository>()
    private val forumRepository = mockk<ForumRepository>()
    private val canyonRepository = mockk<CanyonRepository>()
    private val connectivityObserver = mockk<ConnectivityObserver>()
    private val notificationCenterRepository = mockk<NotificationCenterRepository>()
    private val preferences = FakePreferencesRepository()
    private val outingRepository = mockk<OutingRepository>(relaxUnitFun = true) {
        every { observeAll() } returns flowOf(emptyList())
    }
    private val pendingDebitCount = MutableStateFlow(0)
    private val debitSubmissionRepository = mockk<DebitSubmissionRepository> {
        every { observePendingCount() } returns pendingDebitCount
    }
    private val searchCatalog = MutableStateFlow<List<CanyonSearchItem>>(emptyList())

    @Test
    fun `debit reports waiting to be sent are counted for the home banner`() = runTest {
        every { connectivityObserver.observe() } returns MutableStateFlow(false)
        every { connectivityObserver.isCurrentlyOnline() } returns false
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedCategories() } returns CachedItems(emptyList(), null)
        val viewModel = createViewModel()

        pendingDebitCount.value = 2
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.pendingDebitCount)
    }

    @Test
    fun `offline startup without cached data shows offline empty states`() = runTest {
        val connectivity = MutableStateFlow(false)
        var currentOnline = false
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        preferences.selectedHomeFeed = null
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedCategories() } returns CachedItems(emptyList(), null)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(HomeFeedNotice.OFFLINE_EMPTY, viewModel.uiState.value.debitFeed.notice)
        assertEquals(HomeFeedNotice.OFFLINE_EMPTY, viewModel.uiState.value.forumFeed.notice)
        assertTrue(viewModel.uiState.value.debitFeed.items.isEmpty())
        assertTrue(viewModel.uiState.value.forumFeed.items.isEmpty())
    }

    @Test
    fun `going offline with loaded debits keeps list and shows banner`() = runTest {
        val connectivity = MutableStateFlow(true)
        var currentOnline = true
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        preferences.selectedHomeFeed = null
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedCategories() } returns CachedItems(emptyList(), null)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(listOf(sampleDebit()), 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.success(CachedItems(emptyList(), 1234L))

        val viewModel = createViewModel()
        advanceUntilIdle()

        currentOnline = false
        connectivity.value = false
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.debitFeed.items.size)
        assertEquals(HomeFeedNotice.OFFLINE_BANNER, viewModel.uiState.value.debitFeed.notice)
    }

    @Test
    fun `forum service failure without cache shows service unavailable`() = runTest {
        val connectivity = MutableStateFlow(true)
        var currentOnline = true
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        preferences.selectedHomeFeed = HomeFeedType.FORUM
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedCategories() } returns CachedItems(emptyList(), null)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(emptyList(), 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.failure(IllegalStateException("500"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(HomeFeedType.FORUM, viewModel.uiState.value.selectedFeed)
        assertEquals(HomeFeedNotice.SERVICE_UNAVAILABLE, viewModel.uiState.value.forumFeed.notice)
    }

    @Test
    fun `network failure with cached forum keeps cached topics and shows offline banner`() = runTest {
        val connectivity = MutableStateFlow(true)
        var currentOnline = true
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        preferences.selectedHomeFeed = HomeFeedType.FORUM
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(listOf(sampleTopic()), 5678L)
        coEvery { forumRepository.getCachedCategories() } returns CachedItems(listOf(sampleCategory()), 5678L)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(emptyList(), 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.failure(UnknownHostException("Unable to resolve host"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.forumFeed.items.size)
        assertEquals(HomeFeedNotice.OFFLINE_BANNER, viewModel.uiState.value.forumFeed.notice)
    }

    @Test
    fun `selecting forum persists selection and refreshes forum feed`() = runTest {
        val connectivity = MutableStateFlow(true)
        var currentOnline = true
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        preferences.selectedHomeFeed = null
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedCategories() } returns CachedItems(emptyList(), null)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(listOf(sampleDebit()), 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.success(CachedItems(listOf(sampleTopic()), 5678L))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectFeed(HomeFeedType.FORUM)
        advanceUntilIdle()

        assertEquals(HomeFeedType.FORUM, viewModel.uiState.value.selectedFeed)
        assertEquals(1, viewModel.uiState.value.forumFeed.items.size)
        assertEquals(HomeFeedType.FORUM, preferences.selectedHomeFeed)
    }

    @Test
    fun `restored selected feed does not overwrite user selection made while cache is loading`() = runTest {
        val connectivity = MutableStateFlow(true)
        var currentOnline = true
        val cacheRestoreGate = CompletableDeferred<Unit>()
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        preferences.selectedHomeFeed = HomeFeedType.DEBITS
        coEvery { debitRepository.getCachedLatestDebits(any()) } coAnswers {
            cacheRestoreGate.await()
            CachedItems(listOf(sampleDebit()), 1234L)
        }
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(listOf(sampleTopic()), 5678L)
        coEvery { forumRepository.getCachedCategories() } returns CachedItems(listOf(sampleCategory()), 5678L)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(listOf(sampleDebit()), 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.success(CachedItems(listOf(sampleTopic()), 5678L))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectFeed(HomeFeedType.FORUM)
        advanceUntilIdle()
        cacheRestoreGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(HomeFeedType.FORUM, viewModel.uiState.value.selectedFeed)
        assertEquals(1, viewModel.uiState.value.forumFeed.items.size)
    }

    @Test
    fun `selecting current forum feed refreshes topics`() = runTest {
        val connectivity = MutableStateFlow(true)
        var currentOnline = true
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        preferences.selectedHomeFeed = HomeFeedType.FORUM
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedCategories() } returns CachedItems(emptyList(), null)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(emptyList(), 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.success(CachedItems(listOf(sampleTopic()), 5678L))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectFeed(HomeFeedType.FORUM)
        advanceUntilIdle()

        assertEquals(HomeFeedType.FORUM, viewModel.uiState.value.selectedFeed)
        assertEquals(1, viewModel.uiState.value.forumFeed.items.size)
        coVerify(atLeast = 2) { forumRepository.refreshActiveTopics(any()) }
    }

    @Test
    fun `forum filter counts the active topics of each forum and filters them`() = runTest {
        every { connectivityObserver.observe() } returns MutableStateFlow(true)
        every { connectivityObserver.isCurrentlyOnline() } returns true
        preferences.selectedHomeFeed = HomeFeedType.FORUM
        val swissTopics = listOf(sampleTopic(), sampleTopic().copy(topicId = 28126))
        val italianTopic = sampleTopic().copy(topicId = 30001, forumId = 15, forumName = "ITALIE")
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedCategories() } returns CachedItems(emptyList(), null)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(emptyList(), 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.success(CachedItems(swissTopics + italianTopic, 5678L))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val filter = viewModel.uiState.value.forumFilter
        assertEquals(mapOf("forum:16" to 2, "forum:15" to 1), filter.topicCounts)
        assertEquals(3, filter.totalTopicCount)

        viewModel.selectForumCategory(sampleCategory().key())
        advanceUntilIdle()

        assertEquals(sampleCategory(), viewModel.uiState.value.forumFilter.selectedCategory())
        assertEquals(listOf(28125, 28126), viewModel.uiState.value.forumFeed.items.map { it.topicId })
        assertEquals(mapOf("forum:16" to 2, "forum:15" to 1), viewModel.uiState.value.forumFilter.topicCounts)
    }

    @Test
    fun `latest debits are displayed by pages of twenty`() = runTest {
        val connectivity = MutableStateFlow(true)
        var currentOnline = true
        val debits = (1..25).map { id -> sampleDebit(canyonId = id, canyonNom = "Canyon $id") }
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        preferences.selectedHomeFeed = null
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedCategories() } returns CachedItems(emptyList(), null)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(debits, 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.success(CachedItems(emptyList(), 1234L))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(20, viewModel.uiState.value.debitFeed.items.size)
        assertTrue(viewModel.uiState.value.debitGeoFilter.canLoadMore)

        viewModel.loadMoreDebits()
        advanceUntilIdle()

        assertEquals(25, viewModel.uiState.value.debitFeed.items.size)
        assertEquals(false, viewModel.uiState.value.debitGeoFilter.canLoadMore)
    }

    @Test
    fun `country and subdivision filters apply before latest debit pagination`() = runTest {
        val connectivity = MutableStateFlow(true)
        var currentOnline = true
        val frenchDebits = (1..22).map { id -> sampleDebit(canyonId = id, canyonNom = "France $id") }
        val spanishDebits = (101..105).map { id -> sampleDebit(canyonId = id, canyonNom = "Espagne $id") }
        val debits = frenchDebits + spanishDebits
        searchCatalog.value = frenchDebits.map { debit ->
            searchItem(
                id = debit.canyonId,
                nom = debit.canyonNom.orEmpty(),
                pays = "France",
                departement = if (debit.canyonId <= 2) "Ain" else "Alpes-Maritimes",
            )
        } + spanishDebits.map { debit ->
            searchItem(
                id = debit.canyonId,
                nom = debit.canyonNom.orEmpty(),
                pays = "Espagne",
                departement = "Huesca",
            )
        }
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        preferences.selectedHomeFeed = null
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedCategories() } returns CachedItems(emptyList(), null)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(debits, 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.success(CachedItems(emptyList(), 1234L))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(
            listOf(
                ZoneCountry("Espagne", 5, listOf(ZoneSubdivision("Huesca", 5))),
                ZoneCountry("France", 22, listOf(ZoneSubdivision("Ain", 2), ZoneSubdivision("Alpes-Maritimes", 20))),
            ),
            viewModel.uiState.value.debitGeoFilter.countries,
        )
        assertEquals(27, viewModel.uiState.value.debitGeoFilter.totalCount)

        viewModel.selectDebitZone("France")
        advanceUntilIdle()

        assertEquals(20, viewModel.uiState.value.debitFeed.items.size)
        assertEquals(22, viewModel.uiState.value.debitGeoFilter.filteredCount)
        assertTrue(viewModel.uiState.value.debitGeoFilter.canLoadMore)

        viewModel.selectDebitZone("France", "Ain")
        advanceUntilIdle()

        assertEquals(listOf(1, 2), viewModel.uiState.value.debitFeed.items.map { it.canyonId })
        assertEquals(false, viewModel.uiState.value.debitGeoFilter.canLoadMore)
        assertEquals(ReportZone("France", "Ain"), preferences.latestReportsZone)
    }

    @Test
    fun `the area of the latest reports is restored at launch and can be cleared`() = runTest {
        val debits = (1..3).map { id -> sampleDebit(canyonId = id, canyonNom = "Canyon $id") }
        searchCatalog.value = debits.map { debit ->
            searchItem(
                id = debit.canyonId,
                nom = debit.canyonNom.orEmpty(),
                pays = "France",
                departement = if (debit.canyonId ==
                    1
                ) {
                    "Ain"
                } else {
                    "Var"
                },
            )
        }
        every { connectivityObserver.observe() } returns MutableStateFlow(true)
        every { connectivityObserver.isCurrentlyOnline() } returns true
        preferences.latestReportsZone = ReportZone("France", "Var")
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(debits, 1234L)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedCategories() } returns CachedItems(emptyList(), null)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(debits, 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.success(CachedItems(emptyList(), 1234L))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("Var", viewModel.uiState.value.debitGeoFilter.selectedDepartment)
        assertEquals(listOf(2, 3), viewModel.uiState.value.debitFeed.items.map { it.canyonId })

        viewModel.clearDebitGeoFilter()
        advanceUntilIdle()

        assertEquals(null, preferences.latestReportsZone)
        assertEquals(3, viewModel.uiState.value.debitFeed.items.size)
    }

    @Test
    fun `a refresh that succeeds clears an offline banner the connectivity flow left behind`() = runTest {
        // The flow said "offline" and never said "online" again (e.g. the app was blocked in the background).
        val connectivity = MutableStateFlow(false)
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } returns false
        preferences.selectedHomeFeed = HomeFeedType.DEBITS
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(listOf(sampleDebit()), 1L)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedCategories() } returns CachedItems(emptyList(), null)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(listOf(sampleDebit()), 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.success(CachedItems(emptyList(), 1234L))
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(HomeFeedNotice.OFFLINE_BANNER, viewModel.uiState.value.debitFeed.notice)

        every { connectivityObserver.isCurrentlyOnline() } returns true
        viewModel.refreshSelectedFeed()
        advanceUntilIdle()

        assertEquals(1234L, viewModel.uiState.value.debitFeed.lastSyncedAtEpochMs)
        assertTrue(viewModel.uiState.value.isOnline)
        assertEquals(null, viewModel.uiState.value.debitFeed.notice)
    }

    @Test
    fun `a momentary offline check does not stick while the connectivity flow stays online`() = runTest {
        val connectivity = MutableStateFlow(true)
        var currentOnline = true
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        preferences.selectedHomeFeed = HomeFeedType.DEBITS
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedCategories() } returns CachedItems(emptyList(), null)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(listOf(sampleDebit()), 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.success(CachedItems(emptyList(), 1234L))
        val viewModel = createViewModel()
        advanceUntilIdle()

        currentOnline = false
        viewModel.selectFeed(HomeFeedType.DEBITS)
        advanceUntilIdle()
        currentOnline = true
        viewModel.selectFeed(HomeFeedType.FORUM)
        viewModel.selectFeed(HomeFeedType.DEBITS)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isOnline)
        assertEquals(null, viewModel.uiState.value.debitFeed.notice)
    }

    @Test
    fun `coming back to the screen online refreshes the feeds the connectivity flow left offline`() = runTest {
        val connectivity = MutableStateFlow(false)
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } returns false
        preferences.selectedHomeFeed = HomeFeedType.DEBITS
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(listOf(sampleDebit()), 1L)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedCategories() } returns CachedItems(emptyList(), null)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(listOf(sampleDebit()), 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.success(CachedItems(emptyList(), 1234L))
        val viewModel = createViewModel()
        advanceUntilIdle()

        every { connectivityObserver.isCurrentlyOnline() } returns true
        viewModel.recheckConnectivity()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isOnline)
        assertEquals(null, viewModel.uiState.value.debitFeed.notice)
        assertEquals(1234L, viewModel.uiState.value.debitFeed.lastSyncedAtEpochMs)
        assertEquals(1234L, viewModel.uiState.value.forumFeed.lastSyncedAtEpochMs)
    }

    @Test
    fun `coming back to the screen online retries a feed a blocked refresh left offline`() = runTest {
        // The flow said online while the app was still cut off: the refresh it started found no network.
        val connectivity = MutableStateFlow(true)
        var currentOnline = false
        every { connectivityObserver.observe() } returns connectivity
        every { connectivityObserver.isCurrentlyOnline() } answers { currentOnline }
        preferences.selectedHomeFeed = HomeFeedType.DEBITS
        coEvery { debitRepository.getCachedLatestDebits(any()) } returns CachedItems(listOf(sampleDebit()), 1L)
        coEvery { forumRepository.getCachedActiveTopics(any()) } returns CachedItems(emptyList(), null)
        coEvery { forumRepository.getCachedCategories() } returns CachedItems(emptyList(), null)
        coEvery { debitRepository.refreshLatestDebits(any()) } returns Result.success(CachedItems(listOf(sampleDebit()), 1234L))
        coEvery { forumRepository.refreshActiveTopics(any()) } returns Result.success(CachedItems(emptyList(), 1234L))
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.selectFeed(HomeFeedType.DEBITS)
        advanceUntilIdle()
        assertEquals(HomeFeedNotice.OFFLINE_BANNER, viewModel.uiState.value.debitFeed.notice)

        currentOnline = true
        viewModel.recheckConnectivity()
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.debitFeed.notice)
        assertEquals(1234L, viewModel.uiState.value.debitFeed.lastSyncedAtEpochMs)
    }

    private fun createViewModel(): HomeViewModel {
        every { canyonRepository.observeSearchCatalog() } returns searchCatalog
        every { notificationCenterRepository.observeState() } returns MutableStateFlow(NotificationCenterState())
        coEvery { notificationCenterRepository.toggleForumCategoryFollow(any(), any(), any()) } returns Unit
        coEvery { notificationCenterRepository.toggleForumThreadFollow(any(), any()) } returns Unit
        coEvery { forumRepository.refreshCategories() } returns Result.success(CachedItems(listOf(sampleCategory()), 5678L))
        return HomeViewModel(
            debitRepository = debitRepository,
            forumRepository = forumRepository,
            canyonRepository = canyonRepository,
            connectivityObserver = connectivityObserver,
            preferencesRepository = preferences,
            notificationCenterRepository = notificationCenterRepository,
            debitSubmissionRepository = debitSubmissionRepository,
            outingRepository = outingRepository,
            endOutingUseCase = EndOutingUseCase(outingRepository, mockk(relaxed = true), Clock.systemDefaultZone()),
            clock = Clock.systemDefaultZone(),
        )
    }

    private fun sampleDebit(canyonId: Int = 27, canyonNom: String = "Furon") = Debit(
        canyonId = canyonId,
        canyonNom = canyonNom,
        date = LocalDate.of(2026, 3, 28),
        niveau = NiveauDebit.CORRECT,
        auteur = "Alice",
    )

    private fun searchItem(id: Int, nom: String, pays: String, departement: String): CanyonSearchItem = CanyonSearchItem(
        id = id,
        nom = nom,
        nomComplet = nom,
        pays = pays,
        countryTokens = listOf(pays),
        departement = departement,
        departmentTokens = listOf(departement),
        subdivisionsByCountry = mapOf(pays to listOf(departement)),
        cotation = "V3A3III",
        cotationRating = CotationRating.parse("V3A3III"),
        url = "/canyoning/canyon/$id/test.html",
        searchableText = listOf(nom, pays, departement).joinToString(" ").normalizeForSearch(),
        normalizedNom = nom.normalizeForSearch(),
        normalizedNomComplet = nom.normalizeForSearch(),
    )

    private fun sampleTopic() = ForumActiveTopic(
        topicId = 28125,
        title = "Baisse des notes Gamchi , Trummel IV",
        forumId = 16,
        forumName = "SUISSE",
        replyCount = 34,
        viewCount = 34624,
        lastAuthor = "Max38",
        lastPostedAtText = "ven. 03 avr. 2026 22:20",
        lastPostedAtEpochMs = 1_743_800_454_000,
        topicUrl = "https://www.descente-canyon.com/forums/viewtopic.php?f=16&t=28125",
        lastMessageUrl = "https://www.descente-canyon.com/forums/viewtopic.php?f=16&t=28125&p=305248#p305248",
    )

    private fun sampleCategory() = ForumCategory(
        forumId = 16,
        forumName = "SUISSE",
        forumUrl = "https://www.descente-canyon.com/forums/viewforum.php?f=16",
    )
}
