package fr.descentecanyon.app.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import fr.descentecanyon.app.domain.model.AppLinks

/** Opens [url] with the app that handles it (browser, maps...); returns false when none can. */
fun Context.openUrl(url: String): Boolean = startActivitySafely(Intent(Intent.ACTION_VIEW, url.toUri()))

/**
 * Opens [url]: a link of the app's own domain ([appLinks]) in this app, whatever Android decided for
 * the domain, any other one with the app that handles it.
 */
fun Context.openAppLink(url: String, appLinks: AppLinks): Boolean = if (appLinks.parse(url) != null) {
    startActivitySafely(Intent(Intent.ACTION_VIEW, url.toUri()).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
} else {
    openUrl(url)
}

/** [text] through any messaging app. */
fun Context.shareText(text: String): Boolean =
    startActivitySafely(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))

/** Starts [intent] without crashing when no installed app can handle it. */
fun Context.startActivitySafely(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}
