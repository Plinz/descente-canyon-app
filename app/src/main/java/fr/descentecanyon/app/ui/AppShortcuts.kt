package fr.descentecanyon.app.ui

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import fr.descentecanyon.app.R
import fr.descentecanyon.app.core.ui.R as UiR
import fr.descentecanyon.app.ui.navigation.AppLaunchTarget
import fr.descentecanyon.app.ui.navigation.LaunchSection
import fr.descentecanyon.app.ui.navigation.putLaunchTarget

/**
 * Launcher shortcuts (long press on the app icon). Published at runtime rather than declared in XML:
 * the intent targets this build's package (debug and minified variants have their own) and the
 * labels follow the app language.
 */
object AppShortcuts {

    fun publish(context: Context) {
        // Ranked in this order: emergency first, at hand from the home screen when something goes wrong.
        val shortcuts = listOf(
            emergencyShortcut(context, rank = 0),
            shortcut(
                context,
                LaunchSection.SEARCH,
                UiR.string.tab_search,
                UiR.string.shortcut_search_long,
                R.drawable.ic_shortcut_search,
                rank = 1,
            ),
            shortcut(context, LaunchSection.MAP, UiR.string.tab_map, UiR.string.shortcut_map_long, R.drawable.ic_shortcut_map, rank = 2),
            shortcut(
                context,
                LaunchSection.FAVORITES,
                UiR.string.tab_favorites,
                UiR.string.shortcut_favorites_long,
                R.drawable.ic_shortcut_favorites,
                rank = 3,
            ),
        )
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    /** Tells the launcher the user went to this section, shortcut or not, to rank the shortcuts. */
    fun reportUsed(context: Context, section: LaunchSection) {
        if (section == LaunchSection.PENDING_DEBITS) return
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, section.name.lowercase()) }
    }

    /** Opens the emergency screen on its own, which finds the canyon from the outing or the position. */
    private fun emergencyShortcut(context: Context, rank: Int): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, EMERGENCY_SHORTCUT_ID)
            .setShortLabel(context.getString(UiR.string.emergency_title))
            .setLongLabel(context.getString(UiR.string.shortcut_emergency_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_emergency))
            .setIntent(EmergencyActivity.intent(context))
            .setRank(rank)
            .build()

    private fun shortcut(
        context: Context,
        section: LaunchSection,
        @StringRes shortLabel: Int,
        @StringRes longLabel: Int,
        @DrawableRes icon: Int,
        rank: Int,
    ): ShortcutInfoCompat {
        // The launcher starts shortcuts in a new, cleared task: MainActivity opens the section from onCreate.
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putLaunchTarget(AppLaunchTarget.Section(section))
        return ShortcutInfoCompat.Builder(context, section.name.lowercase())
            .setShortLabel(context.getString(shortLabel))
            .setLongLabel(context.getString(longLabel))
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    private const val EMERGENCY_SHORTCUT_ID = "emergency"
}
