package fr.descentecanyon.app.e2e.outing

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidTest
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.OutingChoices
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.model.PlannedOuting
import fr.descentecanyon.app.domain.model.Stay
import fr.descentecanyon.app.e2e.BaseE2eTest
import fr.descentecanyon.app.ui.test.TestTags
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * An outing is planned from the canyon page and its follow-up turned on, then found again from the
 * home screen and the list of outings; a stay gets a canyon for one of its days.
 */
@HiltAndroidTest
class OutingPlanE2ETest : BaseE2eTest() {

    @Before
    override fun setUp() {
        // Turning the follow-up on asks for the notification permission: granted beforehand, no system dialog.
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
    fun theCanyonPagePlansAnOutingWithItsGroupAndTurnsItsFollowUpOn() {
        openRiolan()
        composeRule.onNodeWithTag(TestTags.DETAIL_LIST).performScrollToNode(hasTestTag(TestTags.DETAIL_PLAN_OUTING))
        // The page runs under the bottom bar: on a small screen (CI) the scroll leaves the card behind it, a tap would hit the bar.
        composeRule.onNodeWithTag(TestTags.DETAIL_PLAN_OUTING).performSemanticsAction(SemanticsActions.OnClick)

        // The answer first: the key times are there before anything is chosen.
        waitForTag(TestTags.OUTING_SUMMARY)
        composeRule.onNodeWithTag(TestTags.OUTING_GROUP_ROW).performClick()
        waitForTag(TestTags.OUTING_GROUP_SIZE)
        composeRule.onNodeWithText(plural(R.plurals.outing_group_size, 4)).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.outing_group_more)).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(
                hasText(plural(R.plurals.outing_group_size, 5)),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        Espresso.pressBack()

        // The change is kept at once, as a draft.
        composeRule.waitUntil(10_000) { runBlocking { outingRepository.observeAll().first().isNotEmpty() } }
        assertFalse(runBlocking { outingRepository.observeAll().first().single().tracked })

        waitForTag(TestTags.OUTING_ACTIVATE_BUTTON)
        composeRule.onNodeWithTag(TestTags.OUTING_ACTIVATE_BUTTON, useUnmergedTree = true).performClick()
        composeRule.waitUntil(10_000) { runBlocking { outingRepository.observeAll().first().singleOrNull()?.tracked == true } }
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
        waitForTag(TestTags.OUTING_STATUS)
    }

    @Test
    fun leavingNowFromTheCanyonPageTurnsTheFollowUpOnAtOnce() {
        openRiolan()
        composeRule.onNodeWithTag(TestTags.DETAIL_LIST).performScrollToNode(hasTestTag(TestTags.DETAIL_START_NOW))
        composeRule.onNodeWithTag(TestTags.DETAIL_START_NOW).performSemanticsAction(SemanticsActions.OnClick)

        composeRule.waitUntil(10_000) { runBlocking { outingRepository.observeAll().first().singleOrNull()?.tracked == true } }
        // Under way at once: the main action is the return.
        waitForTag(TestTags.OUTING_END_BUTTON)
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
        composeRule.onNodeWithText(plural(R.plurals.outing_group_size, 6), substring = true).assertExists()
    }

    @Test
    fun aDayOfAStayGetsACanyonFromTheCanyonsAround() {
        val today = LocalDate.now()
        val stayId = runBlocking {
            val place = placeRepository.save(Place("Camping du Riolan", 43.86, 6.78))
            stayRepository.save(Stay(place, today, today.plusDays(2)))
        }
        val day = today.plusDays(1)

        composeRule.onNodeWithTag(TestTags.HOME_ACCOUNT_BUTTON, useUnmergedTree = true).performClick()
        waitForTag(TestTags.MY_ACCOUNT_LIST)
        composeRule.onNodeWithTag(TestTags.MY_ACCOUNT_LIST).performScrollToNode(hasText(string(R.string.account_outings_all)))
        composeRule.onNodeWithText(string(R.string.account_outings_all)).performClick()

        waitForTag(TestTags.stayRow(stayId))
        composeRule.onNodeWithTag(TestTags.stayRow(stayId), useUnmergedTree = true).performClick()
        waitForTag(TestTags.STAY_LIST)
        composeRule.onNodeWithTag(TestTags.STAY_LIST).performScrollToNode(hasTestTag(TestTags.stayChoose(day.toString())))
        composeRule.onNodeWithTag(TestTags.stayChoose(day.toString()), useUnmergedTree = true).performClick()

        waitForTag(TestTags.stayCandidate(101))
        composeRule.onNodeWithTag(TestTags.stayCandidate(101), useUnmergedTree = true).performClick()

        // The canyon is kept at once for that day of the stay, as a draft.
        waitForTag(TestTags.OUTING_SUMMARY)
        composeRule.waitUntil(10_000) { runBlocking { outingRepository.observeAll().first().isNotEmpty() } }
        val draft = runBlocking { outingRepository.observeAll().first().single() }
        assertEquals(stayId, draft.stayId)
        assertEquals(day, draft.choices?.date)
        assertFalse(draft.tracked)

        // Back to the stay: the day shows its canyon.
        Espresso.pressBack()
        waitForTag(TestTags.STAY_LIST)
        composeRule.onNodeWithTag(TestTags.STAY_LIST).performScrollToNode(hasTestTag(TestTags.outingRow(draft.id)))
        assertTrue(composeRule.onAllNodes(hasTestTag(TestTags.outingRow(draft.id))).fetchSemanticsNodes().isNotEmpty())
    }

    private fun openRiolan() {
        waitForTag(TestTags.HOME_QUICK_SEARCH)
        composeRule.onNodeWithTag(TestTags.HOME_QUICK_SEARCH, useUnmergedTree = true).performClick()
        waitForTag(TestTags.SEARCH_QUERY_FIELD)
        composeRule.onNodeWithTag(TestTags.SEARCH_QUERY_FIELD, useUnmergedTree = true).performTextInput("Riolan")
        waitForTag(TestTags.canyonCard(101))
        composeRule.onNodeWithTag(TestTags.canyonCard(101), useUnmergedTree = true).performClick()
        waitForTag(TestTags.DETAIL_LIST)
    }

    private fun plural(resId: Int, count: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.resources.getQuantityString(resId, count, count)
}
