package fr.descentecanyon.app.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.domain.model.FirstStep
import fr.descentecanyon.app.domain.model.Hint
import fr.descentecanyon.app.domain.model.OnboardingState
import fr.descentecanyon.app.domain.model.agenda
import fr.descentecanyon.app.domain.model.currentHint
import fr.descentecanyon.app.domain.repository.FavoritesRepository
import fr.descentecanyon.app.domain.repository.OnboardingRepository
import fr.descentecanyon.app.domain.repository.OutingRepository
import fr.descentecanyon.app.ui.onboarding.OnboardingHost
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The onboarding of the app, held by the activity and given to the screens as [OnboardingHost]: a new
 * install is welcomed, its first steps check themselves as they are done (a favorite, an outing are
 * read from their data), and the hints come one at a time, none while an outing is under way.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @param:ApplicationContext context: Context,
    private val repository: OnboardingRepository,
    favoritesRepository: FavoritesRepository,
    outingRepository: OutingRepository,
    clock: Clock,
) : ViewModel(),
    OnboardingHost {

    override var state: OnboardingState by mutableStateOf(repository.state.value)
        private set

    private var outingUnderway by mutableStateOf(false)
    private val requested = mutableStateListOf<Hint>()

    /** On screen when a hint was closed: none of them shows before it leaves the screen. */
    private val blocked = mutableStateListOf<Hint>()

    override val currentHint: Hint?
        get() = currentHint(
            requested = requested,
            seen = state.seenHints,
            blocked = blocked.toSet(),
            enabled = state.isSetUp && !state.showWelcome && !outingUnderway,
        )

    init {
        // Decided before the first screen is drawn: the welcome, not the home screen, for a new install.
        repository.setUp(freshInstall = context.isFreshInstall())
        state = repository.state.value
        viewModelScope.launch { repository.state.collect { state = it } }
        viewModelScope.launch {
            favoritesRepository.getFavorites().collect { favorites -> if (favorites.isNotEmpty()) markStepDone(FirstStep.ADD_FAVORITE) }
        }
        viewModelScope.launch {
            outingRepository.observeAll().agenda(clock).collect { agenda ->
                outingUnderway = agenda.underway != null
                if (agenda.underway != null || agenda.upcoming.isNotEmpty()) markStepDone(FirstStep.PLAN_OUTING)
            }
        }
    }

    fun completeWelcome() = repository.completeWelcome()

    override fun requestHint(hint: Hint) {
        if (hint !in requested) requested += hint
    }

    override fun releaseHint(hint: Hint) {
        requested -= hint
        blocked -= hint
    }

    override fun dismissHint(hint: Hint) {
        repository.markHintSeen(hint)
        blocked.clear()
        blocked += requested
    }

    override fun markStepDone(step: FirstStep) = repository.markStepDone(step)

    override fun dismissFirstSteps() = repository.dismissFirstSteps()

    override fun dismissWhatsNew() = repository.dismissWhatsNew()

    override fun replay() {
        blocked.clear()
        repository.replay()
    }
}

/** Installed, not updated: the app was never opened before this version. */
private fun Context.isFreshInstall(): Boolean = runCatching {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
    }
    info.firstInstallTime == info.lastUpdateTime
}.getOrDefault(false)
