package fr.descentecanyon.app.e2e.outing

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidTest
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.e2e.BaseE2eTest
import fr.descentecanyon.app.ui.test.TestTags
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** The emergency screen is one tap away and names the canyon by itself. */
@HiltAndroidTest
class EmergencyE2ETest : BaseE2eTest() {

    @Test
    fun theCanyonPageOpensItsEmergencyScreenInOneTap() {
        waitForTag(TestTags.HOME_QUICK_SEARCH)
        composeRule.onNodeWithTag(TestTags.HOME_QUICK_SEARCH, useUnmergedTree = true).performClick()
        waitForTag(TestTags.SEARCH_QUERY_FIELD)
        composeRule.onNodeWithTag(TestTags.SEARCH_QUERY_FIELD, useUnmergedTree = true).performTextInput("Riolan")
        waitForTag(TestTags.canyonCard(101))
        composeRule.onNodeWithTag(TestTags.canyonCard(101), useUnmergedTree = true).performClick()

        waitForTag(TestTags.EMERGENCY_BUTTON)
        composeRule.onNodeWithTag(TestTags.EMERGENCY_BUTTON, useUnmergedTree = true).performClick()

        assertEmergencyFor("Riolan")
        // A French canyon: 112, then the national numbers.
        composeRule.onNodeWithTag(TestTags.emergencyNumber("15"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theHomeScreenOpensTheEmergencyScreenOfTheOutingUnderWay() {
        val now = Instant.now()
        runBlocking {
            outingRepository.save(
                PlannedOuting(
                    canyonId = 101,
                    canyonName = "Riolan",
                    start = now.minus(Duration.ofHours(1)),
                    expectedBack = now.plus(Duration.ofHours(3)),
                    alertAt = now.plus(Duration.ofHours(5)),
                ),
            )
        }

        waitForTag(TestTags.EMERGENCY_BUTTON)
        composeRule.onNodeWithTag(TestTags.EMERGENCY_BUTTON, useUnmergedTree = true).performClick()

        assertEmergencyFor("Riolan")
    }

    private fun assertEmergencyFor(canyonName: String) {
        val canyonCard = hasTestTag(TestTags.EMERGENCY_CANYON) and hasAnyDescendant(hasText(canyonName))
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(canyonCard, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TestTags.emergencyNumber("112"), useUnmergedTree = true).assertIsDisplayed()
    }
}
