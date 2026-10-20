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

    @Test
    fun `notification targets of an outing survive the intent round trip`() {
        listOf(AppLaunchTarget.OutingBack(7, 42), AppLaunchTarget.OutingPlan(7, 42), AppLaunchTarget.DebitForm(42)).forEach { target ->
            assertEquals(target, consumeLaunchTarget(Intent().putLaunchTarget(target)))
        }
        assertEquals(AppLaunchTarget.None, consumeLaunchTarget(Intent().putLaunchTarget(AppLaunchTarget.OutingBack(0, 42))))
        assertEquals(AppLaunchTarget.None, consumeLaunchTarget(Intent().putLaunchTarget(AppLaunchTarget.OutingPlan(7, 0))))
    }
}
