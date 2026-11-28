package fr.descentecanyon.app.ui.outing

import androidx.lifecycle.SavedStateHandle
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.model.AppCanyons
import fr.descentecanyon.app.domain.model.CanyonSearchItem
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.domain.model.GroupLevel
import fr.descentecanyon.app.domain.model.ShareCodec
import fr.descentecanyon.app.domain.model.SharedOuting
import fr.descentecanyon.app.domain.model.SharedOutingSummary
import fr.descentecanyon.app.domain.model.normalizeForSearch
import fr.descentecanyon.app.domain.repository.AppServerRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.domain.repository.ShareRepository
import fr.descentecanyon.app.testutil.FakeOutingRepository
import fr.descentecanyon.app.testutil.FakePlaceRepository
import fr.descentecanyon.app.testutil.FakeStayRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedLinkViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val catalog = MutableStateFlow(listOf(searchItem(42, "Riolan")))
    private val canyonRepository = mockk<CanyonRepository> {
        every { observeSearchCatalog() } returns catalog
    }
    private val appServerRepository = mockk<AppServerRepository>()

    @Test
    fun `a canyon added in the app since the last catalog update is asked to the server once, then the outing can be planned`() = runTest {
        coEvery { appServerRepository.refreshIfOlderThan(any()) } answers {
            catalog.value = catalog.value + searchItem(APP_CANYON, "Gorge proposée")
            Result.success(Unit)
        }

        val viewModel = viewModel(outing(APP_CANYON))
        advanceUntilIdle()

        val state = viewModel.uiState.value as SharedLinkUiState.Outing
        assertTrue(state.canPlan)
        assertEquals("Gorge proposée", state.canyons.single().name)
        coVerify(exactly = 1) { appServerRepository.refreshIfOlderThan(any()) }
    }

    @Test
    fun `a canyon the server does not bring yet keeps the outing unplannable, and every known canyon asks nothing`() = runTest {
        coEvery { appServerRepository.refreshIfOlderThan(any()) } returns Result.success(Unit)

        val missing = viewModel(outing(APP_CANYON))
        advanceUntilIdle()
        assertFalse((missing.uiState.value as SharedLinkUiState.Outing).canPlan)
        coVerify(exactly = 1) { appServerRepository.refreshIfOlderThan(any()) }

        val known = viewModel(outing(42))
        advanceUntilIdle()
        assertTrue((known.uiState.value as SharedLinkUiState.Outing).canPlan)
        coVerify(exactly = 1) { appServerRepository.refreshIfOlderThan(any()) }
    }

    private fun viewModel(outing: SharedOuting) = SharedLinkViewModel(
        savedStateHandle = SavedStateHandle(mapOf("payload" to ShareCodec.pack(outing))),
        shareRepository = mockk<ShareRepository>(),
        canyonRepository = canyonRepository,
        outingRepository = FakeOutingRepository(),
        stayRepository = FakeStayRepository(),
        placeRepository = FakePlaceRepository(),
        favoritesRepository = mockk<FavoritesRepository>(),
        connectivityObserver = mockk<ConnectivityObserver> { every { isCurrentlyOnline() } returns true },
        appServerRepository = appServerRepository,
        clock = Clock.fixed(Instant.parse("2027-07-01T06:00:00Z"), ZoneOffset.UTC),
    )

    private fun outing(canyonId: Int) = SharedOuting(
        canyonId = canyonId,
        date = LocalDate.of(2027, 7, 3),
        anchorTime = LocalTime.of(8, 30),
        groupSize = 4,
        groupLevel = GroupLevel.AUTONOMOUS,
        summary = SharedOutingSummary(listOf("Gorge")),
    )

    private fun searchItem(id: Int, name: String) = CanyonSearchItem(
        id = id,
        nom = name,
        nomComplet = name,
        pays = "France",
        cotation = "v3a3II",
        cotationRating = CotationRating.parse("v3a3II"),
        representativeLat = 43.9,
        representativeLng = 6.9,
        url = "",
        searchableText = name.normalizeForSearch(),
        normalizedNom = name.normalizeForSearch(),
        normalizedNomComplet = name.normalizeForSearch(),
    )

    private companion object {
        const val APP_CANYON = AppCanyons.FIRST_ID + 12
    }
}
