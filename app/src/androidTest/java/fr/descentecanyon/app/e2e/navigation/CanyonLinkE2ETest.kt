package fr.descentecanyon.app.e2e.navigation

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.net.toUri
import dagger.hilt.android.testing.HiltAndroidTest
import fr.descentecanyon.app.e2e.BaseE2eTest
import fr.descentecanyon.app.ui.test.TestTags
import org.junit.Assert.assertTrue
import org.junit.Test

/** A canyon link opens its page once: the screens reached from it stay when the activity is recreated. */
@HiltAndroidTest
class CanyonLinkE2ETest : BaseE2eTest() {

    @Test
    fun aRecreatedActivityKeepsItsScreenInsteadOfOpeningTheLinkAgain() {
        relaunchWith {
            action = Intent.ACTION_VIEW
            data = "https://www.descente-canyon.com/canyoning/canyon/101/".toUri()
        }
        waitForTag(TestTags.DETAIL_LIST)
        composeRule.onNodeWithTag(TestTags.DETAIL_LIST).performScrollToNode(hasTestTag(TestTags.DETAIL_PLAN_OUTING))
        // The page runs under the bottom bar: on a small screen (CI) a tap could hit the bar.
        composeRule.onNodeWithTag(TestTags.DETAIL_PLAN_OUTING).performSemanticsAction(SemanticsActions.OnClick)
        waitForTag(TestTags.OUTING_SUMMARY)

        // A rotation, a new font size: the plan is back, and the canyon page is not opened again over it.
        recreateActivity()
        waitForTag(TestTags.OUTING_SUMMARY)
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag(TestTags.DETAIL_LIST, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag(TestTags.OUTING_SUMMARY, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }
}
