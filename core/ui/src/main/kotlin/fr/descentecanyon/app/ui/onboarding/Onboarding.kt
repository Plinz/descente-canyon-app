package fr.descentecanyon.app.ui.onboarding

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.FirstStep
import fr.descentecanyon.app.domain.model.Hint
import fr.descentecanyon.app.domain.model.OnboardingState
import fr.descentecanyon.app.ui.test.TestTags
import kotlinx.coroutines.delay

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
 * [content] with its hint: [title] and [text] over it (or under it, [below]) the first time its
 * screen is visited, one hint at a time. "Compris" or a tap elsewhere closes it for good.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DcHint(
    hint: Hint,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    below: Boolean = false,
    content: @Composable () -> Unit,
) {
    val host = LocalOnboarding.current
    DisposableEffect(host, hint) {
        host.requestHint(hint)
        onDispose { host.releaseHint(hint) }
    }
    val isShown = host.currentHint == hint
    val tooltipState = rememberTooltipState(isPersistent = true)
    LaunchedEffect(isShown) {
        if (isShown) {
            // Once the screen has settled: a hint over a screen still sliding in is missed.
            delay(HINT_DELAY_MS)
            tooltipState.show()
        } else {
            tooltipState.dismiss()
        }
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            if (below) TooltipAnchorPosition.Below else TooltipAnchorPosition.Above,
        ),
        tooltip = {
            RichTooltip(
                modifier = Modifier.testTag(TestTags.hint(hint.name)),
                title = { Text(title) },
                action = {
                    TextButton(onClick = { host.dismissHint(hint) }) { Text(stringResource(R.string.hint_got_it)) }
                },
            ) { Text(text) }
        },
        state = tooltipState,
        onDismissRequest = { if (isShown) host.dismissHint(hint) },
        enableUserInput = false,
        modifier = modifier,
        content = content,
    )
}

private const val HINT_DELAY_MS = 700L
