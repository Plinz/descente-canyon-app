package fr.descentecanyon.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {

    @Test
    fun `one hint at a time, each once, none closed in the same visit, none when disabled`() {
        val onScreen = listOf(Hint.CANYON_PLAN, Hint.CANYON_REPORT)

        assertEquals(Hint.CANYON_PLAN, currentHint(onScreen, seen = emptySet(), blocked = emptySet(), enabled = true))
        // The first one closed: the second waits for another visit of the page.
        assertNull(currentHint(onScreen, seen = setOf(Hint.CANYON_PLAN), blocked = onScreen.toSet(), enabled = true))
        assertEquals(Hint.CANYON_REPORT, currentHint(onScreen, seen = setOf(Hint.CANYON_PLAN), blocked = emptySet(), enabled = true))
        assertNull(currentHint(onScreen, seen = onScreen.toSet(), blocked = emptySet(), enabled = true))
        assertNull(currentHint(onScreen, seen = emptySet(), blocked = emptySet(), enabled = false))
    }

    @Test
    fun `a new install is welcomed then guided, an update told the news, nothing before it is decided`() {
        assertFalse(OnboardingState().showWelcome)
        assertFalse(OnboardingState().showWhatsNew)

        val fresh = OnboardingState(isSetUp = true, welcomeDone = false, firstStepsDismissed = false)
        assertTrue(fresh.showWelcome)
        // The first steps come once the welcome is over, until all are done.
        assertFalse(fresh.showFirstSteps)
        assertTrue(fresh.copy(welcomeDone = true).showFirstSteps)
        assertFalse(fresh.copy(welcomeDone = true, stepsDone = FirstStep.entries.toSet()).showFirstSteps)
        assertFalse(fresh.showWhatsNew)

        val update = OnboardingState(isSetUp = true, whatsNewSeen = 0)
        assertTrue(update.showWhatsNew)
        assertFalse(update.showWelcome)
        assertFalse(update.showFirstSteps)
    }
}
