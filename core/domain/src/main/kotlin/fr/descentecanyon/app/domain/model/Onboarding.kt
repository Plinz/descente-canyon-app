package fr.descentecanyon.app.domain.model

/** A hint shown once, at the first visit of its screen, over the element it explains. */
enum class Hint {
    CANYON_PLAN,
    CANYON_REPORT,
    EXPLORE_MODE,
    OUTINGS_STAY,
    PLAN_ALERT,
    ME_FOLLOWS,
}

/** The steps of "Premiers pas" on the home screen, each checked once done, from wherever. */
enum class FirstStep {
    OPEN_CANYON,
    ADD_FAVORITE,
    PLAN_OUTING,
    SEE_EMERGENCY,
}

/**
 * Where the user is in the onboarding. It is decided once ([isSetUp]): a new install is welcomed and
 * guided by the first steps, an update is told what changed ([showWhatsNew]); hints come in both.
 */
data class OnboardingState(
    val isSetUp: Boolean = false,
    val welcomeDone: Boolean = true,
    val seenHints: Set<Hint> = emptySet(),
    val stepsDone: Set<FirstStep> = emptySet(),
    val firstStepsDismissed: Boolean = true,
    /** The last news shown, compared with [WHATS_NEW_VERSION]. */
    val whatsNewSeen: Int = WHATS_NEW_VERSION,
) {
    val showWelcome: Boolean get() = isSetUp && !welcomeDone

    val showFirstSteps: Boolean get() = isSetUp && welcomeDone && !firstStepsDismissed && stepsDone.size < FirstStep.entries.size

    val showWhatsNew: Boolean get() = isSetUp && whatsNewSeen < WHATS_NEW_VERSION

    companion object {
        /** Raised with each release whose news the home screen tells. */
        const val WHATS_NEW_VERSION = 1
    }
}

/**
 * The hint to show among those whose element is on screen ([requested], in the order they came): one
 * at a time, each once, and never a second one during the same visit of a screen ([blocked]: those on
 * screen when a hint was closed, until they leave it).
 */
fun currentHint(requested: List<Hint>, seen: Set<Hint>, blocked: Set<Hint>, enabled: Boolean): Hint? =
    if (enabled) requested.firstOrNull { it !in seen && it !in blocked } else null
