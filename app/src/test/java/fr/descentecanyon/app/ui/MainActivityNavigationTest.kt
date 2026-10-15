package fr.descentecanyon.app.ui

import androidx.navigation.navOptions
import fr.descentecanyon.app.ui.navigation.BottomNavItem
import fr.descentecanyon.app.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityNavigationTest {

    @Test
    fun `home click stops after successful pop to home`() {
        var popCalled = false
        var navigatedTo: Screen? = null

        handleBottomNavClick(
            item = BottomNavItem.HOME,
            popToHome = {
                popCalled = true
                true
            },
            navigate = { screen -> navigatedTo = screen },
        )

        assertTrue(popCalled)
        assertEquals(null, navigatedTo)
    }

    @Test
    fun `home click navigates when pop to home fails`() {
        var navigatedTo: Screen? = null

        handleBottomNavClick(
            item = BottomNavItem.HOME,
            popToHome = { false },
            navigate = { screen -> navigatedTo = screen },
        )

        assertEquals(Screen.Home, navigatedTo)
    }

    @Test
    fun `non home click always navigates to its destination`() {
        var popCalled = false
        var navigatedTo: Screen? = null

        handleBottomNavClick(
            item = BottomNavItem.SEARCH,
            popToHome = {
                popCalled = true
                true
            },
            navigate = { screen -> navigatedTo = screen },
        )

        assertEquals(false, popCalled)
        assertEquals(Screen.Search, navigatedTo)
    }

    @Test
    fun `detail screen has no selected bottom nav item`() {
        val selected = selectedBottomNavItemForScreen(Screen.CanyonDetail(canyonId = 42))

        assertNull(selected)
    }

    @Test
    fun `canyon points map has no selected bottom nav item`() {
        val selected = selectedBottomNavItemForScreen(Screen.CanyonPointsMap(canyonId = 42))

        assertNull(selected)
    }

    @Test
    fun `app root navigation keeps start destination as back fallback`() {
        val options = navOptions { applyAppRootNavigation(startDestinationId = 123) }

        assertEquals(123, options.popUpToId)
        assertTrue(options.shouldLaunchSingleTop())
        assertFalse(options.shouldRestoreState())
        assertFalse(options.isPopUpToInclusive())
    }
}
