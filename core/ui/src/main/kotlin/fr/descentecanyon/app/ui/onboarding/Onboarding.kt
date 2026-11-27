package fr.descentecanyon.app.ui.onboarding

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import fr.descentecanyon.app.domain.model.FirstStep
import fr.descentecanyon.app.domain.model.Hint
import fr.descentecanyon.app.domain.model.OnboardingState

/**
 * What the screens ask of the onboarding (the activity holds it): the state to show the first steps
 * and the news, the hint to show now, and the steps done.
 */
interface OnboardingHost {
    /** Read in composition: the screens follow it. */
    val state: OnboardingState

    /** The one hint to show now, among those whose element is on screen. */
    val currentHint: Hint?

    /** The element of [hint] is on screen. */
    fun requestHint(hint: Hint)

    /** The element of [hint] left the screen. */
    fun releaseHint(hint: Hint)

    /** Closed by the user: never again. */
    fun dismissHint(hint: Hint)

    fun markStepDone(step: FirstStep)

    fun dismissFirstSteps()

    fun dismissWhatsNew()

    /** "Revoir les astuces". */
    fun replay()
}

/** No onboarding (previews, a screen used alone): nothing is shown. */
object NoOnboarding : OnboardingHost {
    override val state = OnboardingState()
    override val currentHint: Hint? = null

    override fun requestHint(hint: Hint) = Unit

    override fun releaseHint(hint: Hint) = Unit

    override fun dismissHint(hint: Hint) = Unit

    override fun markStepDone(step: FirstStep) = Unit

    override fun dismissFirstSteps() = Unit

    override fun dismissWhatsNew() = Unit

    override fun replay() = Unit
}

val LocalOnboarding = staticCompositionLocalOf<OnboardingHost> { NoOnboarding }

/**
 * [content] with its hint: [title] and [text], shown by [HintOverlay] over a dimmed screen with
 * [content] lit, the first time its screen is visited, one hint at a time. Tapping [content] uses it
 * as usual and closes the hint for good.
 */
@Composable
fun DcHint(hint: Hint, title: String, text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val host = LocalOnboarding.current
    val overlay = LocalHintOverlay.current
    DisposableEffect(host, overlay, hint) {
        host.requestHint(hint)
        onDispose {
            host.releaseHint(hint)
            overlay?.remove(hint)
        }
    }
    val isCurrent = host.currentHint == hint
    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates -> overlay?.place(hint, HintAnchor(coordinates.boundsInRoot(), title, text)) }
            // Seen, not consumed: the element still gets its tap.
            .pointerInput(isCurrent) {
                if (!isCurrent) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    host.dismissHint(hint)
                }
            },
    ) { content() }
}
