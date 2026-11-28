package fr.descentecanyon.app.ui.navigation

import fr.descentecanyon.app.domain.model.AppLinks
import fr.descentecanyon.app.domain.model.CanyonSection
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

    private val appLinks = AppLinks("dc-app.fr")

    @Test
    fun `links of the app's domain open the canyon, its tab or its map`() {
        assertEquals(
            AppLaunchTarget.CanyonDetail(canyonId = 2895, openDebitsTab = false),
            parseLaunchTargetFromUrl("https://dc-app.fr/c/2895-riolan", appLinks),
        )
        assertEquals(
            AppLaunchTarget.CanyonDetail(canyonId = 2895, openDebitsTab = false, section = CanyonSection.DEBITS),
            parseLaunchTargetFromUrl("https://dc-app.fr/c/2895-riolan/debits", appLinks),
        )
        assertEquals(
            AppLaunchTarget.CanyonMap(2895, 44.1, 6.5),
            parseLaunchTargetFromUrl("https://dc-app.fr/c/2895/carte?lat=44.1&lon=6.5", appLinks),
        )
        assertEquals(AppLaunchTarget.Share("k3F9x2pQa"), parseLaunchTargetFromUrl("https://dc-app.fr/s/k3F9x2pQa", appLinks))
    }

    @Test
    fun `the app's domain is ignored without its host, the site's links still open`() {
        assertEquals(AppLaunchTarget.None, parseLaunchTargetFromUrl("https://dc-app.fr/c/2895"))
        assertEquals(
            AppLaunchTarget.CanyonDetail(canyonId = 212, openDebitsTab = false),
            parseLaunchTargetFromUrl("https://www.descente-canyon.com/canyoning/canyon/212/Rabou.html", appLinks),
        )
    }

    @Test
    fun `the link Google Play kept from a shared page is read from its referrer`() {
        assertEquals(
            "https://dc-app.fr/s/k3F9x2pQa",
            InstallReferrerLink.linkOf("dc_link=https%3A%2F%2Fdc-app.fr%2Fs%2Fk3F9x2pQa"),
        )
        assertEquals(
            "https://dc-app.fr/o/2224-riolan#eJzLSM3JyVcozy_KSQEAGgQEXQ",
            InstallReferrerLink.linkOf(
                "utm_source=x&dc_link=https%3A%2F%2Fdc-app.fr%2Fo%2F2224-riolan%23eJzLSM3JyVcozy_KSQEAGgQEXQ",
            ),
        )
        assertEquals(null, InstallReferrerLink.linkOf("utm_source=google-play&utm_medium=organic"))
        assertEquals(null, InstallReferrerLink.linkOf("dc_link=javascript%3Aalert(1)"))
    }
}
