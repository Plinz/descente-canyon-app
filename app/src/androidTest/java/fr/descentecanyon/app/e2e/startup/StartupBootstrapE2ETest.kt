package fr.descentecanyon.app.e2e.startup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dagger.hilt.android.testing.HiltAndroidTest
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.e2e.BaseE2eTest
import fr.descentecanyon.app.ui.test.TestTags
import org.junit.Test

@HiltAndroidTest
class StartupBootstrapE2ETest : BaseE2eTest() {
    @Test
    fun coldStartShowsHomeAndLatestDebits() {
        waitForTag(TestTags.HOME_QUICK_SEARCH)

        composeRule.onNodeWithTag(TestTags.HOME_QUICK_SEARCH, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.last_debits))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Riolan")
            .assertIsDisplayed()
    }
}
