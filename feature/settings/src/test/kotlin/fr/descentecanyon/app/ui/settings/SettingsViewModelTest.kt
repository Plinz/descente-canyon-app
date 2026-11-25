package fr.descentecanyon.app.ui.settings

import app.cash.turbine.test
import fr.descentecanyon.app.domain.model.AppServerState
import fr.descentecanyon.app.domain.model.ThemeMode
import fr.descentecanyon.app.domain.repository.AppServerRepository
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.testutil.FakePreferencesRepository
import fr.descentecanyon.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val preferences = FakePreferencesRepository(themeMode = ThemeMode.DARK)
    private val pendingCount = MutableStateFlow(2)
    private val canyonRepository = mockk<CanyonRepository> {
        coEvery { getCatalogSize() } returns 4127
    }
    private val server = FakeAppServerRepository()

    @Test
    fun `state shows the theme and the catalog size`() = runTest {
        val viewModel = SettingsViewModel(preferences, canyonRepository, server)

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.catalogSize == null) state = awaitItem()
            assertEquals(SettingsUiState(themeMode = ThemeMode.DARK, catalogSize = 4127, server = server.state.value), state)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial state already has the saved theme`() {
        val viewModel = SettingsViewModel(preferences, canyonRepository, server)

        assertEquals(ThemeMode.DARK, viewModel.uiState.value.themeMode)
    }

    @Test
    fun `choosing a theme saves it`() = runTest {
        val viewModel = SettingsViewModel(preferences, canyonRepository, server)

        viewModel.uiState.test {
            awaitItem()
            viewModel.setThemeMode(ThemeMode.LIGHT)
            var state = awaitItem()
            while (state.themeMode != ThemeMode.LIGHT) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(ThemeMode.LIGHT, preferences.getThemeMode())
    }

    @Test
    fun `switching the server back on checks it at once`() = runTest {
        val viewModel = SettingsViewModel(preferences, canyonRepository, server)

        viewModel.setServerEnabled(false)
        viewModel.setServerEnabled(true)
        advanceUntilIdle()

        assertTrue(server.state.value.isEnabled)
        assertEquals(1, server.refreshes)
        assertFalse(viewModel.uiState.value.isCheckingServer)
    }

    private class FakeAppServerRepository : AppServerRepository {
        val state = MutableStateFlow(AppServerState(isConfigured = true))
        var refreshes = 0

        override fun observeState() = state

        override suspend fun refresh(): Result<Unit> {
            refreshes++
            return Result.success(Unit)
        }

        override suspend fun setEnabled(enabled: Boolean) {
            state.value = state.value.copy(isEnabled = enabled)
        }

        override suspend fun setCatalogOnMobileData(enabled: Boolean) {
            state.value = state.value.copy(catalogOnMobileData = enabled)
        }

        override suspend fun dismissMessage(id: String) = Unit
    }
}
