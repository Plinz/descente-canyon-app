package fr.descentecanyon.app.e2e

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import fr.descentecanyon.app.e2e.fakes.E2eFixtureState
import fr.descentecanyon.app.e2e.fakes.FakeConnectivityObserver
import fr.descentecanyon.app.ui.MainActivity
import javax.inject.Inject
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

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    open fun setUp() {
        hiltRule.inject()
        E2eFixtureState.reset()
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

    private fun launchMainActivity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        scenario = ActivityScenario.launch(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        composeRule.waitForIdle()
    }
}
