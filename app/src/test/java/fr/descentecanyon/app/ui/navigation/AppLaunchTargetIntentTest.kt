package fr.descentecanyon.app.ui.navigation

import android.app.Application
import android.content.Intent
import fr.descentecanyon.app.ui.toScreen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppLaunchTargetIntentTest {

    @Test
    fun `section targets survive the intent round trip`() {
        LaunchSection.entries.forEach { section ->
            val intent = Intent().putLaunchTarget(AppLaunchTarget.Section(section))

            assertEquals(AppLaunchTarget.Section(section), consumeLaunchTarget(intent))
        }
    }

    @Test
    fun `a consumed target is not delivered twice`() {
        val intent = Intent().putLaunchTarget(AppLaunchTarget.Section(LaunchSection.PENDING_DEBITS))

        consumeLaunchTarget(intent)

        assertEquals(AppLaunchTarget.None, consumeLaunchTarget(intent))
    }

    @Test
    fun `unknown sections are ignored`() {
        val intent = Intent()
            .putExtra("launch_target_kind", "section")
            .putExtra("launch_target_section", "NOT_A_SECTION")

        assertEquals(AppLaunchTarget.None, consumeLaunchTarget(intent))
    }

    @Test
    fun `launcher shortcuts open their screen`() {
        assertEquals(Screen.Search, LaunchSection.SEARCH.toScreen())
        assertEquals(Screen.PendingDebits, LaunchSection.PENDING_DEBITS.toScreen())
    }
}
