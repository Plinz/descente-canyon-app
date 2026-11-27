package fr.descentecanyon.app.e2e.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidTest
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.e2e.BaseE2eTest
import fr.descentecanyon.app.ui.navigation.BottomNavItem
import fr.descentecanyon.app.ui.test.TestTags
import org.junit.Test

@HiltAndroidTest
class SearchToFavoritesE2ETest : BaseE2eTest() {
    @Test
    fun searchOpenDetailAndPersistFavoriteInFavoritesTab() {
        waitForTag(TestTags.HOME_QUICK_SEARCH)

        composeRule.onNodeWithTag(TestTags.HOME_QUICK_SEARCH, useUnmergedTree = true)
            .performClick()

        waitForTag(TestTags.SEARCH_QUERY_FIELD)
        composeRule.onNodeWithTag(TestTags.SEARCH_QUERY_FIELD, useUnmergedTree = true)
            .performTextInput("Riolan")

        waitForTag(TestTags.canyonCard(101))
        composeRule.onNodeWithTag(TestTags.canyonCard(101), useUnmergedTree = true)
            .performClick()

        waitForTag(TestTags.DETAIL_FAVORITE_BUTTON)
        composeRule.onNodeWithTag(TestTags.DETAIL_FAVORITE_BUTTON, useUnmergedTree = true)
            .performClick()

        // The canyon page has its own action bar, not the tabs: back to Explorer first.
        composeRule.onNodeWithContentDescription(string(R.string.back), useUnmergedTree = true).performClick()
        waitForTag(TestTags.bottomNavItem(BottomNavItem.FAVORITES.name))
        composeRule.onNodeWithTag(TestTags.bottomNavItem(BottomNavItem.FAVORITES.name))
            .performClick()

        waitForTag(TestTags.FAVORITES_LIST)
        composeRule.onNodeWithTag(TestTags.FAVORITES_LIST, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.canyonCard(101), useUnmergedTree = true)
            .assertIsDisplayed()
    }
}
