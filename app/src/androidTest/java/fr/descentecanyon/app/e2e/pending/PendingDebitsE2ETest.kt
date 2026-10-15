package fr.descentecanyon.app.e2e.pending

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidTest
import fr.descentecanyon.app.domain.model.AirTemperature
import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.model.WaterTemperature
import fr.descentecanyon.app.e2e.BaseE2eTest
import fr.descentecanyon.app.e2e.fakes.E2eFixtureState
import fr.descentecanyon.app.ui.test.TestTags
import java.time.LocalDate
import org.junit.Test

@HiltAndroidTest
class PendingDebitsE2ETest : BaseE2eTest() {
    @Test
    fun queuedDebitIsListedFromHomeBannerAndCanBeDeleted() {
        relaunchWithConnectivity(isOnline = false)
        E2eFixtureState.queuedSubmissions.value = listOf(
            DebitSubmission(
                canyonId = 101,
                observerName = "Testeur",
                observerEmail = "test@example.com",
                observationDate = LocalDate.of(2026, 10, 14),
                observationType = ObservationType.PARCOURU,
                debitLevel = NiveauDebit.CORRECT,
                waterTemperature = WaterTemperature.INCONNUE,
                airTemperature = AirTemperature.INCONNUE,
                comment = "Observation E2E",
            ),
        )

        waitForTag(TestTags.HOME_PENDING_DEBITS_BANNER)
        composeRule.onNodeWithTag(TestTags.HOME_PENDING_DEBITS_BANNER, useUnmergedTree = true).performClick()

        waitForTag(TestTags.pendingDebitDelete(1))
        composeRule.onNodeWithText("Riolan", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Observation E2E", useUnmergedTree = true).assertIsDisplayed()

        composeRule.onNodeWithTag(TestTags.pendingDebitDelete(1), useUnmergedTree = true).performClick()
        waitForTag(TestTags.PENDING_DEBIT_DELETE_CONFIRM)
        composeRule.onNodeWithTag(TestTags.PENDING_DEBIT_DELETE_CONFIRM, useUnmergedTree = true).performClick()

        waitForTag(TestTags.PENDING_DEBITS_EMPTY)
    }
}
