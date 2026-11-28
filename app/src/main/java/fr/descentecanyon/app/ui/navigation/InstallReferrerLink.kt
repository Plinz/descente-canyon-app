package fr.descentecanyon.app.ui.navigation

import android.content.Context
import androidx.core.content.edit
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import fr.descentecanyon.app.domain.model.AppLinks
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The link a page of the app's domain passed to Google Play ("Installer l'application" on a shared
 * canyon or outing): read once, at the first start after the installation, so that the person lands
 * on what was shared instead of the home screen.
 */
object InstallReferrerLink {
    private const val PREFERENCES = "install_referrer"
    private const val KEY_READ = "read"
    private const val PARAMETER = "dc_link"
    private const val TIMEOUT_MS = 3_000L

    /** The shared link to open, once per installation; null when the app was not installed from one. */
    suspend fun consume(context: Context, appLinks: AppLinks): String? {
        if (!appLinks.isConfigured) return null
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(KEY_READ, false)) return null
        val referrer = withTimeoutOrNull(TIMEOUT_MS) { read(context) }
        // Once, whatever the answer: an installation from elsewhere (GitHub, a test) has none.
        preferences.edit { putBoolean(KEY_READ, true) }
        return referrer?.let(::linkOf)?.takeIf { appLinks.parse(it) != null }
    }

    /** "dc_link=https%3A%2F%2F…" (the referrer as Google Play gives it) → the link. */
    internal fun linkOf(referrer: String): String? = referrer.split('&')
        .firstOrNull { it.startsWith("$PARAMETER=") }
        ?.substringAfter('=')
        ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8) }.getOrNull() }
        ?.takeIf { it.startsWith("https://") }

    private suspend fun read(context: Context): String? = suspendCancellableCoroutine { continuation ->
        val client = InstallReferrerClient.newBuilder(context).build()
        fun finish(value: String?) {
            runCatching { client.endConnection() }
            if (continuation.isActive) continuation.resume(value)
        }
        continuation.invokeOnCancellation { runCatching { client.endConnection() } }
        runCatching {
            client.startConnection(
                object : InstallReferrerStateListener {
                    override fun onInstallReferrerSetupFinished(responseCode: Int) {
                        val value = if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                            runCatching { client.installReferrer.installReferrer }.getOrNull()
                        } else {
                            null
                        }
                        finish(value)
                    }

                    override fun onInstallReferrerServiceDisconnected() = finish(null)
                },
            )
        }.onFailure { finish(null) }
    }
}
