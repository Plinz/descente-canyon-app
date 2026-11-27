package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.FirstStep
import fr.descentecanyon.app.domain.model.Hint
import fr.descentecanyon.app.domain.model.OnboardingState
import kotlinx.coroutines.flow.StateFlow

/** What the user has seen of the onboarding, kept on the phone; read at once, so the first screen is the right one. */
interface OnboardingRepository {
    val state: StateFlow<OnboardingState>

    /** Once: a [freshInstall] gets the welcome and the first steps, an update the news of this release. */
    fun setUp(freshInstall: Boolean)

    fun completeWelcome()

    fun markHintSeen(hint: Hint)

    fun markStepDone(step: FirstStep)

    fun dismissFirstSteps()

    fun dismissWhatsNew()

    /** "Revoir les astuces": the hints and the first steps again. */
    fun replay()

    /** Everything seen, nothing shown (E2E tests). */
    fun skipAll()
}
