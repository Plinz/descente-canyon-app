package fr.descentecanyon.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLaunchTargetTest {

    @Test
    fun `parses canyon deep link on www host`() {
        val target = parseLaunchTargetFromUrl(
            "https://www.descente-canyon.com/canyoning/canyon/212/Rabou.html",
        )

        assertEquals(AppLaunchTarget.CanyonDetail(canyonId = 212, openDebitsTab = false), target)
    }

    @Test
    fun `parses canyon deep link on bare host`() {
        val target = parseLaunchTargetFromUrl(
            "https://descente-canyon.com/canyoning/canyon/2412",
        )

        assertEquals(AppLaunchTarget.CanyonDetail(canyonId = 2412, openDebitsTab = false), target)
    }

    @Test
    fun `ignores unrelated deep links`() {
        val target = parseLaunchTargetFromUrl(
            "https://www.descente-canyon.com/canyoning/forum/212",
        )

        assertEquals(AppLaunchTarget.None, target)
    }
}
