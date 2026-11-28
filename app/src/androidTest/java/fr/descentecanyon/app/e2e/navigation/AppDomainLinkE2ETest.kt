package fr.descentecanyon.app.e2e.navigation

import android.content.Intent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.net.toUri
import androidx.test.espresso.Espresso
import dagger.hilt.android.testing.HiltAndroidTest
import fr.descentecanyon.app.BuildConfig
import fr.descentecanyon.app.e2e.BaseE2eTest
import fr.descentecanyon.app.ui.test.TestTags
import org.junit.Test

/** Links of the app's own domain, as a message or a QR code gives them: the canyon, its tab, its map. */
@HiltAndroidTest
class AppDomainLinkE2ETest : BaseE2eTest() {

    @Test
    fun aCanyonLinkOpensItsPageOnTheTabItNames() {
        relaunchWith {
            action = Intent.ACTION_VIEW
            data = "https://${BuildConfig.LINK_HOST}/c/101-canyon/debits".toUri()
        }
        waitForTag(TestTags.DETAIL_LIST)
        waitForTag(TestTags.detailTab(DEBITS_TAB))
        composeRule.onNodeWithTag(TestTags.detailTab(DEBITS_TAB), useUnmergedTree = true).assertIsSelected()
    }

    @Test
    fun aMapLinkOpensTheMapOverTheCanyonPage() {
        relaunchWith {
            action = Intent.ACTION_VIEW
            data = "https://${BuildConfig.LINK_HOST}/c/101/carte".toUri()
        }
        waitForTag(TestTags.CANYON_POINTS_MAP)
        // Back: the canyon the link is about.
        Espresso.pressBack()
        waitForTag(TestTags.DETAIL_LIST)
    }

    private companion object {
        const val DEBITS_TAB = 3
    }
}
