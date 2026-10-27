package fr.descentecanyon.app.e2e

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import fr.descentecanyon.app.domain.model.GearListSettings
import fr.descentecanyon.app.domain.model.OutingSettings
import fr.descentecanyon.app.domain.repository.OutingPreferencesRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.domain.repository.PlaceRepository
import fr.descentecanyon.app.domain.repository.PreferencesRepository
import fr.descentecanyon.app.domain.repository.StayRepository
import fr.descentecanyon.app.e2e.fakes.E2eFixtureState
import fr.descentecanyon.app.e2e.fakes.FakeConnectivityObserver
import fr.descentecanyon.app.ui.MainActivity
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule

/** Base of the end-to-end tests; each concrete test class must be annotated with `@HiltAndroidTest`. */
abstract class BaseE2eTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var fakeConnectivityObserver: FakeConnectivityObserver

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    @Inject
    lateinit var outingRepository: OutingRepository

    @Inject
    lateinit var placeRepository: PlaceRepository

    @Inject
    lateinit var stayRepository: StayRepository

    @Inject
    lateinit var outingPreferencesRepository: OutingPreferencesRepository

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    open fun setUp() {
        hiltRule.inject()
        E2eFixtureState.reset()
        // Local repositories are real and share the app's data: state left by a manual run must not leak in.
        runBlocking {
            preferencesRepository.setLatestReportsZone(null)
            outingRepository.clear()
            placeRepository.observeAll().first().forEach { placeRepository.delete(it.id) }
            stayRepository.observeAll().first().forEach { stayRepository.delete(it.id) }
            outingPreferencesRepository.setSettings(OutingSettings())
            outingPreferencesRepository.setGearList(GearListSettings())
        }
        fakeConnectivityObserver.setOnline(true)
        launchMainActivity()
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    protected fun relaunchWithConnectivity(isOnline: Boolean) {
        scenario?.close()
        E2eFixtureState.reset()
        fakeConnectivityObserver.setOnline(isOnline)
        launchMainActivity()
    }

    /** Resolves UI text from resources so the tests do not depend on the device language. */
    protected fun string(@StringRes resId: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(resId)

    protected fun waitForTag(tag: String, timeoutMillis: Long = 15_000) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    protected fun waitForTagToDisappear(tag: String, timeoutMillis: Long = 15_000) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
    }

    /** Restarts the app as if [configure] had built the launch intent (deep link, notification...). */
    protected fun relaunchWith(configure: Intent.() -> Unit) {
        scenario?.close()
        launchMainActivity(configure)
    }

    private fun launchMainActivity(configure: Intent.() -> Unit = {}) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        scenario = ActivityScenario.launch(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply(configure),
        )
        composeRule.waitForIdle()
    }
}
