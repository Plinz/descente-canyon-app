package fr.descentecanyon.app.e2e.debit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidTest
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.e2e.BaseE2eTest
import fr.descentecanyon.app.ui.test.TestTags
import org.junit.Test

@HiltAndroidTest
class OfflineDebitSyncE2ETest : BaseE2eTest() {
    @Test
    fun offlineDebitIsQueuedThenSyncedWhenConnectivityReturns() {
        relaunchWithConnectivity(isOnline = false)

        waitForTag(TestTags.HOME_QUICK_SEARCH)
        composeRule.onNodeWithTag(TestTags.HOME_QUICK_SEARCH, useUnmergedTree = true).performClick()

        waitForTag(TestTags.SEARCH_QUERY_FIELD)
        composeRule.onNodeWithTag(TestTags.SEARCH_QUERY_FIELD, useUnmergedTree = true)
            .performTextInput("Riolan")

        waitForTag(TestTags.canyonCard(101))
        composeRule.onNodeWithTag(TestTags.canyonCard(101), useUnmergedTree = true)
            .performClick()

        waitForTag(TestTags.DETAIL_REPORT_DEBIT_BUTTON)
        composeRule.onNodeWithTag(TestTags.DETAIL_REPORT_DEBIT_BUTTON, useUnmergedTree = true)
            .performClick()

        waitForTag(TestTags.DEBIT_OBSERVER_NAME_FIELD)
        composeRule.onNodeWithTag(TestTags.DEBIT_OBSERVER_NAME_FIELD, useUnmergedTree = true)
            .performTextInput("Testeur")
        composeRule.onNodeWithTag(TestTags.DEBIT_OBSERVER_EMAIL_FIELD, useUnmergedTree = true)
            .performTextInput("test@example.com")
        composeRule.onNodeWithText(string(R.string.debit_type_descended), useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(string(R.string.debit_level_correct), useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(TestTags.DEBIT_COMMENT_FIELD, useUnmergedTree = true)
            .performScrollTo()
            .performTextInput("Observation E2E")
        // The submit button sits in the scaffold bottom bar, outside the scrollable form.
        composeRule.onNodeWithTag(TestTags.DEBIT_SUBMIT_BUTTON, useUnmergedTree = true)
            .performClick()

        waitForTag(TestTags.DETAIL_REPORT_DEBIT_BUTTON)
        composeRule.onNodeWithTag(TestTags.DETAIL_REPORT_DEBIT_BUTTON, useUnmergedTree = true)
            .performClick()

        waitForTag(TestTags.DEBIT_PENDING_COUNT)
        composeRule.onNodeWithTag(TestTags.DEBIT_PENDING_COUNT, useUnmergedTree = true)
            .assertIsDisplayed()

        fakeConnectivityObserver.setOnline(true)

        waitForTagToDisappear(TestTags.DEBIT_PENDING_COUNT)
        composeRule.onNodeWithTag(TestTags.DEBIT_SUBMIT_BUTTON, useUnmergedTree = true)
            .assertIsDisplayed()
    }
}
