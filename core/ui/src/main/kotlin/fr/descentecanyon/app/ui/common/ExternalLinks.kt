package fr.descentecanyon.app.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/** Opens [url] with the app that handles it (browser, maps...); returns false when none can. */
fun Context.openUrl(url: String): Boolean = startActivitySafely(Intent(Intent.ACTION_VIEW, url.toUri()))

/** Starts [intent] without crashing when no installed app can handle it. */
fun Context.startActivitySafely(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}
