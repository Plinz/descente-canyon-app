package fr.descentecanyon.app.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import fr.descentecanyon.app.domain.model.FirstStep
import fr.descentecanyon.app.domain.model.Hint
import fr.descentecanyon.app.domain.model.OnboardingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OnboardingRepositoryImplTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `a new install is decided once, and what is seen is kept for the next opening`() {
        val repository = OnboardingRepositoryImpl(context)
        repository.setUp(freshInstall = true)
        // Decided once: an update afterwards changes nothing.
        repository.setUp(freshInstall = false)
        assertTrue(repository.state.value.showWelcome)

        repository.completeWelcome()
        repository.markHintSeen(Hint.CANYON_PLAN)
        repository.markStepDone(FirstStep.OPEN_CANYON)

        val reopened = OnboardingRepositoryImpl(context).state.value
        assertFalse(reopened.showWelcome)
        assertTrue(reopened.showFirstSteps)
        assertFalse(reopened.showWhatsNew)
        assertEquals(setOf(Hint.CANYON_PLAN), reopened.seenHints)
        assertEquals(setOf(FirstStep.OPEN_CANYON), reopened.stepsDone)
    }

    @Test
    fun `an update is told the news once, and the tips can be seen again`() {
        val repository = OnboardingRepositoryImpl(context)
        repository.setUp(freshInstall = false)
        assertTrue(repository.state.value.showWhatsNew)
        assertFalse(repository.state.value.showFirstSteps)

        repository.dismissWhatsNew()
        repository.markHintSeen(Hint.EXPLORE_MODE)
        assertEquals(OnboardingState.WHATS_NEW_VERSION, repository.state.value.whatsNewSeen)

        repository.replay()
        assertEquals(emptySet<Hint>(), repository.state.value.seenHints)
        assertTrue(repository.state.value.showFirstSteps)

        repository.skipAll()
        assertFalse(repository.state.value.showFirstSteps)
        assertEquals(Hint.entries.toSet(), repository.state.value.seenHints)
    }
}
