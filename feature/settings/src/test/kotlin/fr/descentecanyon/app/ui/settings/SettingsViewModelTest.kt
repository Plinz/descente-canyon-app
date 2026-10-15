package fr.descentecanyon.app.ui.settings

import app.cash.turbine.test
import fr.descentecanyon.app.domain.model.ThemeMode
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.testutil.FakePreferencesRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val preferences = FakePreferencesRepository(themeMode = ThemeMode.DARK)
    private val pendingCount = MutableStateFlow(2)
    private val debitSubmissionRepository = mockk<DebitSubmissionRepository> {
        every { observePendingCount() } returns pendingCount
    }
    private val canyonRepository = mockk<CanyonRepository> {
        coEvery { getCatalogSize() } returns 4127
    }

    @Test
    fun `state shows the theme, the pending reports and the catalog size`() = runTest {
        val viewModel = SettingsViewModel(preferences, debitSubmissionRepository, canyonRepository)

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.catalogSize == null) state = awaitItem()
            assertEquals(SettingsUiState(themeMode = ThemeMode.DARK, pendingDebitCount = 2, catalogSize = 4127), state)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial state already has the saved theme`() {
        val viewModel = SettingsViewModel(preferences, debitSubmissionRepository, canyonRepository)

        assertEquals(ThemeMode.DARK, viewModel.uiState.value.themeMode)
    }

    @Test
    fun `choosing a theme saves it`() = runTest {
        val viewModel = SettingsViewModel(preferences, debitSubmissionRepository, canyonRepository)

        viewModel.uiState.test {
            awaitItem()
            viewModel.setThemeMode(ThemeMode.LIGHT)
            var state = awaitItem()
            while (state.themeMode != ThemeMode.LIGHT) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(ThemeMode.LIGHT, preferences.getThemeMode())
    }
}
