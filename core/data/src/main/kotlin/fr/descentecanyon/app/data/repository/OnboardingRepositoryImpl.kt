package fr.descentecanyon.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.domain.model.FirstStep
import fr.descentecanyon.app.domain.model.Hint
import fr.descentecanyon.app.domain.model.OnboardingState
import fr.descentecanyon.app.domain.repository.OnboardingRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The onboarding in its own preferences file: read once at start, written as it goes. */
@Singleton
class OnboardingRepositoryImpl @Inject constructor(@param:ApplicationContext private val context: Context) : OnboardingRepository {

    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val mutableState by lazy { MutableStateFlow(read()) }

    override val state: StateFlow<OnboardingState> get() = mutableState.asStateFlow()

    override fun setUp(freshInstall: Boolean) {
        if (mutableState.value.isSetUp) return
        write {
            putBoolean(KEY_SET_UP, true)
            putBoolean(KEY_WELCOME_DONE, !freshInstall)
            putBoolean(KEY_FIRST_STEPS_DISMISSED, !freshInstall)
            // A new install has nothing to compare with: the news are for those who update.
            putInt(KEY_WHATS_NEW_SEEN, if (freshInstall) OnboardingState.WHATS_NEW_VERSION else 0)
        }
    }

    override fun completeWelcome() = write { putBoolean(KEY_WELCOME_DONE, true) }

    override fun markHintSeen(hint: Hint) {
        if (hint in mutableState.value.seenHints) return
        write { putStringSet(KEY_SEEN_HINTS, (mutableState.value.seenHints + hint).mapTo(mutableSetOf()) { it.name }) }
    }

    override fun markStepDone(step: FirstStep) {
        if (step in mutableState.value.stepsDone) return
        write { putStringSet(KEY_STEPS_DONE, (mutableState.value.stepsDone + step).mapTo(mutableSetOf()) { it.name }) }
    }

    override fun dismissFirstSteps() = write { putBoolean(KEY_FIRST_STEPS_DISMISSED, true) }

    override fun dismissWhatsNew() = write { putInt(KEY_WHATS_NEW_SEEN, OnboardingState.WHATS_NEW_VERSION) }

    override fun replay() = write {
        remove(KEY_SEEN_HINTS)
        putBoolean(KEY_FIRST_STEPS_DISMISSED, false)
    }

    override fun skipAll() = write {
        putBoolean(KEY_SET_UP, true)
        putBoolean(KEY_WELCOME_DONE, true)
        putBoolean(KEY_FIRST_STEPS_DISMISSED, true)
        putInt(KEY_WHATS_NEW_SEEN, OnboardingState.WHATS_NEW_VERSION)
        putStringSet(KEY_SEEN_HINTS, Hint.entries.mapTo(mutableSetOf()) { it.name })
    }

    /** Written at once in memory (the file follows), so the next screen already sees it. */
    private fun write(change: SharedPreferences.Editor.() -> Unit) {
        prefs.edit(action = change)
        mutableState.value = read()
    }

    private fun read(): OnboardingState = OnboardingState(
        isSetUp = prefs.getBoolean(KEY_SET_UP, false),
        welcomeDone = prefs.getBoolean(KEY_WELCOME_DONE, true),
        seenHints = prefs.names(KEY_SEEN_HINTS).mapNotNull { name -> Hint.entries.firstOrNull { it.name == name } }.toSet(),
        stepsDone = prefs.names(KEY_STEPS_DONE).mapNotNull { name -> FirstStep.entries.firstOrNull { it.name == name } }.toSet(),
        firstStepsDismissed = prefs.getBoolean(KEY_FIRST_STEPS_DISMISSED, true),
        whatsNewSeen = prefs.getInt(KEY_WHATS_NEW_SEEN, OnboardingState.WHATS_NEW_VERSION),
    )

    private fun SharedPreferences.names(key: String): Set<String> = getStringSet(key, null).orEmpty()

    private companion object {
        const val PREFS = "onboarding"
        const val KEY_SET_UP = "set_up"
        const val KEY_WELCOME_DONE = "welcome_done"
        const val KEY_SEEN_HINTS = "seen_hints"
        const val KEY_STEPS_DONE = "steps_done"
        const val KEY_FIRST_STEPS_DISMISSED = "first_steps_dismissed"
        const val KEY_WHATS_NEW_SEEN = "whats_new_seen"
    }
}
