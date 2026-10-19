package fr.descentecanyon.app.ui.account

import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.AppException
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.domain.model.CanyonNote
import fr.descentecanyon.app.domain.model.CanyonSummary
import fr.descentecanyon.app.domain.model.ContributorIdentity
import fr.descentecanyon.app.domain.model.ForumUser
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.NotificationCenterState
import fr.descentecanyon.app.domain.model.SiteLogbookImport
import fr.descentecanyon.app.domain.repository.AuthRepository
import fr.descentecanyon.app.domain.repository.CanyonNotesRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.domain.repository.ForumUserRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import fr.descentecanyon.app.domain.repository.NotificationCenterRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.PersonalDataRepository
import fr.descentecanyon.app.domain.usecase.ImportSiteLogbookUseCase
import fr.descentecanyon.app.testutil.FakePreferencesRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import fr.descentecanyon.app.ui.common.UiText
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MyAccountViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val authState = MutableStateFlow<AuthState>(AuthState.Disconnected)
    private val authRepository = mockk<AuthRepository> { every { authState } returns this@MyAccountViewModelTest.authState }
    private val forumUserRepository = mockk<ForumUserRepository> {
        coEvery { getByUsername("Membre") } returns ForumUser(username = "Membre", debitReportCount = 30, forumPostCount = 12)
    }
    private val preferences = FakePreferencesRepository(contributorIdentity = ContributorIdentity("Antoine", "a@example.com"))
    private val logbook = MutableStateFlow(
        listOf(
            LogbookEntry(id = 2, canyonId = 26, canyonName = "Furon", date = LocalDate.of(2026, 7, 30)),
            LogbookEntry(id = 1, canyonId = 2283, canyonName = "Ga", date = LocalDate.of(2025, 7, 14)),
        ),
    )
    private val logbookRepository = mockk<LogbookRepository> { every { observeAll() } returns logbook }
    private val canyonNotesRepository = mockk<CanyonNotesRepository> {
        every { observeAll() } returns flowOf(listOf(CanyonNote(26, "Parking après le pont", 1_000L)))
    }
    private val canyonRepository = mockk<CanyonRepository> {
        coEvery { getCanyonSummaries(setOf(26)) } returns
            listOf(CanyonSummary(id = 26, nom = "Furon", pays = "France", cotation = "v3a3II", url = ""))
    }
    private val outingRepository = mockk<OutingRepository> { every { observeCurrent() } returns flowOf(null) }
    private val debitSubmissionRepository = mockk<DebitSubmissionRepository> { every { observePendingCount() } returns flowOf(1) }
    private val notificationCenterRepository = mockk<NotificationCenterRepository> {
        every { observeState() } returns
            flowOf(NotificationCenterState())
    }
    private val personalDataRepository = mockk<PersonalDataRepository>()
    private val importSiteLogbookUseCase = mockk<ImportSiteLogbookUseCase>()

    private fun viewModel() = MyAccountViewModel(
        authRepository = authRepository,
        forumUserRepository = forumUserRepository,
        preferencesRepository = preferences,
        logbookRepository = logbookRepository,
        canyonNotesRepository = canyonNotesRepository,
        canyonRepository = canyonRepository,
        outingRepository = outingRepository,
        debitSubmissionRepository = debitSubmissionRepository,
        notificationCenterRepository = notificationCenterRepository,
        personalDataRepository = personalDataRepository,
        importSiteLogbookUseCase = importSiteLogbookUseCase,
        clock = Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `sums up the account, logbook, memos and contributions`() = runTest {
        val viewModel = viewModel()
        authState.value = AuthState.Connected("Membre")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(30, state.member?.debitReportCount)
        assertEquals(2, state.stats.outings)
        assertEquals(1, state.stats.outingsThisYear)
        assertEquals(listOf("Furon", "Ga"), state.recentEntries.map { it.canyonName })
        assertEquals(listOf(MemoPreview(26, "Furon", "Parking après le pont")), state.recentMemos)
        assertEquals(1, state.pendingDebitCount)
        assertEquals(ContributorIdentity("Antoine", "a@example.com"), state.contributorIdentity)
    }

    @Test
    fun `importing the list of the site tells how many outings it added`() = runTest {
        coEvery { importSiteLogbookUseCase() } returnsMany listOf(
            Result.success(SiteLogbookImport(descents = 29, added = 27, completed = 2)),
            Result.success(SiteLogbookImport(descents = 29, added = 0, completed = 29)),
        )
        val viewModel = viewModel()

        viewModel.importFromSite()
        advanceUntilIdle()
        assertEquals(UiText.plural(R.plurals.account_import_site_done, 27, 27), viewModel.uiState.value.message)

        viewModel.onMessageShown()
        viewModel.importFromSite()
        advanceUntilIdle()
        assertEquals(UiText.res(R.string.account_import_site_up_to_date), viewModel.uiState.value.message)
        assertEquals(false, viewModel.uiState.value.isImportingFromSite)
    }

    @Test
    fun `an import with an expired session asks to log in again`() = runTest {
        coEvery { importSiteLogbookUseCase() } returns Result.failure(AppException(AppError.SessionExpired))
        val viewModel = viewModel()

        viewModel.importFromSite()
        advanceUntilIdle()

        assertEquals(UiText.res(R.string.account_import_site_login), viewModel.uiState.value.loginRequest)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `the identity of reports without an account can be changed and forgotten`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.saveContributorIdentity(" Antoine D ", " ")
        advanceUntilIdle()
        assertEquals(ContributorIdentity("Antoine D", null), preferences.contributorIdentity)

        viewModel.clearContributorIdentity()
        advanceUntilIdle()
        assertNull(preferences.contributorIdentity)
        assertNull(viewModel.uiState.value.contributorIdentity)
    }
}
