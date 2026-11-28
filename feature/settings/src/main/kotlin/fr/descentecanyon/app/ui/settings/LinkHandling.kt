package fr.descentecanyon.app.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import fr.descentecanyon.app.ui.common.startActivitySafely

/**
 * Since Android 12 a link opens an app only for the domains the app is allowed to: verified (the
 * app's own domain, from its assetlinks.json) or chosen by the person in the app's "Open by
 * default" settings (descente-canyon.com, which the app cannot verify). The domains still left to
 * the browser, empty when every link opens here or before Android 12.
 */
fun Context.domainsOpenedInBrowser(): List<String> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
    return LinkDomains.notApproved(this)
}

/** The app's "Open by default" settings, where its links are allowed. */
fun Context.openLinkSettings() {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.fromParts("package", packageName, null))
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    }
    if (!startActivitySafely(intent)) {
        startActivitySafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private object LinkDomains {
    fun notApproved(context: Context): List<String> {
        val manager = context.getSystemService(DomainVerificationManager::class.java) ?: return emptyList()
        val state = runCatching { manager.getDomainVerificationUserState(context.packageName) }.getOrNull() ?: return emptyList()
        val hosts = state.hostToStateMap
        // Switched off as a whole, or host by host; the www. variant is named with its domain (both are asked for).
        val blocked = if (state.isLinkHandlingAllowed) {
            hosts.filterValues {
                it == DomainVerificationUserState.DOMAIN_STATE_NONE
            }.keys
        } else {
            hosts.keys
        }
        return blocked.map { it.removePrefix("www.") }.distinct().sorted()
    }
}
