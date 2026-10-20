package fr.descentecanyon.app.e2e.outing

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidTest
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.OutingChoices
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.e2e.BaseE2eTest
import fr.descentecanyon.app.ui.test.TestTags
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** An outing is planned from the canyon page, then found again from the home screen and the list of outings. */
@HiltAndroidTest
class OutingPlanE2ETest : BaseE2eTest() {

    @Before
    override fun setUp() {
        // Saving an outing asks for the notification permission: granted beforehand, no system dialog.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        super.setUp()
    }

    @Test
    fun theCanyonPagePlansAnOutingWithItsGroupAndSavesIt() {
        waitForTag(TestTags.HOME_QUICK_SEARCH)
        composeRule.onNodeWithTag(TestTags.HOME_QUICK_SEARCH, useUnmergedTree = true).performClick()
        waitForTag(TestTags.SEARCH_QUERY_FIELD)
        composeRule.onNodeWithTag(TestTags.SEARCH_QUERY_FIELD, useUnmergedTree = true).performTextInput("Riolan")
        waitForTag(TestTags.canyonCard(101))
        composeRule.onNodeWithTag(TestTags.canyonCard(101), useUnmergedTree = true).performClick()

        waitForTag(TestTags.DETAIL_LIST)
        composeRule.onNodeWithTag(TestTags.DETAIL_LIST).performScrollToNode(hasTestTag(TestTags.DETAIL_PLAN_OUTING))
        composeRule.onNodeWithTag(TestTags.DETAIL_PLAN_OUTING, useUnmergedTree = true).performClick()

        waitForTag(TestTags.OUTING_TIMELINE)
        composeRule.onNodeWithText(plural(R.plurals.outing_group_size, 4)).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.outing_group_more)).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTextCount(plural(R.plurals.outing_group_size, 5)) > 0
        }

        composeRule.onNodeWithTag(TestTags.OUTING_PLAN_LIST).performScrollToNode(hasTestTag(TestTags.OUTING_SAVE_BUTTON))
        composeRule.onNodeWithTag(TestTags.OUTING_SAVE_BUTTON, useUnmergedTree = true).performClick()

        composeRule.waitUntil(10_000) { runBlocking { outingRepository.observeAll().first().isNotEmpty() } }
        val outing = runBlocking { outingRepository.observeAll().first().single() }
        assertEquals(101, outing.canyonId)
        assertEquals(5, outing.groupSize)
        // Walk start after 20 min at the parking (×1.15 for five people).
        assertEquals(
            Duration.ofMinutes(23),
            Duration.between(
                outing.choices!!.date.atTime(outing.choices!!.anchorTime).atZone(ZoneId.systemDefault()).toInstant(),
                outing.start,
            ),
        )
    }

    @Test
    fun aPlannedOutingIsFoundFromTheHomeScreenAndTheListOfOutings() {
        val start = Instant.now().plus(Duration.ofDays(1))
        val id = runBlocking {
            outingRepository.save(
                PlannedOuting(
                    canyonId = 101,
                    canyonName = "Riolan",
                    start = start,
                    expectedBack = start.plus(Duration.ofHours(3)),
                    alertAt = start.plus(Duration.ofHours(5)),
                    groupSize = 6,
                    choices = OutingChoices(
                        date = start.atZone(ZoneId.systemDefault()).toLocalDate(),
                        anchorTime = LocalTime.of(9, 0),
                        groupSize = 6,
                    ),
                ),
            )
        }
        relaunchWith {}

        waitForTag(TestTags.HOME_UPCOMING_OUTINGS)
        composeRule.onNodeWithText(string(R.string.home_all_outings)).performClick()
        waitForTag(TestTags.outingRow(id))
        composeRule.onNodeWithTag(TestTags.outingRow(id), useUnmergedTree = true).performClick()

        waitForTag(TestTags.OUTING_STATUS)
        composeRule.onNodeWithText(plural(R.plurals.outing_group_size, 6)).assertExists()
    }

    private fun plural(resId: Int, count: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.resources.getQuantityString(resId, count, count)

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size
}
