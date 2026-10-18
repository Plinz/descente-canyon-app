package fr.descentecanyon.app.e2e.navigation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import dagger.hilt.android.testing.HiltAndroidTest
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.e2e.BaseE2eTest
import fr.descentecanyon.app.ui.test.TestTags
import org.junit.Test

@HiltAndroidTest
class BackNavigationE2ETest : BaseE2eTest() {

    @Test
    fun aDoubleTapOnBackDuringTheTransitionKeepsTheHomeScreen() {
        // A canyon link opens the page right above the home screen.
        relaunchWith {
            action = Intent.ACTION_VIEW
            data = "https://www.descente-canyon.com/canyoning/canyon/101/".toUri()
        }
        waitForTag(TestTags.DETAIL_FAVORITE_BUTTON)

        // The second tap lands while the page is still fading out.
        composeRule.mainClock.autoAdvance = false
        val back = composeRule.onNodeWithContentDescription(string(R.string.back), useUnmergedTree = true)
        back.performClick()
        composeRule.mainClock.advanceTimeBy(FRAME_MS)
        back.performClick()
        composeRule.mainClock.autoAdvance = true

        waitForTag(TestTags.HOME_QUICK_SEARCH)
        composeRule.onNodeWithTag(TestTags.HOME_QUICK_SEARCH, useUnmergedTree = true).assertIsDisplayed()
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
