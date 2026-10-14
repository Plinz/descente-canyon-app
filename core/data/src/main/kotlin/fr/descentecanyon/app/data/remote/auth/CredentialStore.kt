@file:Suppress("DEPRECATION")

package fr.descentecanyon.app.data.remote.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.data.network.SiteCookieJar
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Secure storage of the descente-canyon.com session. The password is never stored: a session is
 * restored from its cookies and an expired session asks the user to log in again.
 */
interface CredentialStore {
    fun getUsername(): String?
    fun getSessionCookies(): List<Cookie>
    fun saveSession(username: String, cookies: List<Cookie>)
    fun updateSessionCookies(cookies: List<Cookie>)

    /** Forgets the session; [keepUsername] keeps the username to prefill the next login. */
    fun clearSession(keepUsername: Boolean = false)
}

/** Implementation using EncryptedSharedPreferences (AES-256, Android Keystore-backed). */
@Singleton
class EncryptedCredentialStore @Inject constructor(@param:ApplicationContext private val context: Context) : CredentialStore {

    private val prefs: SharedPreferences by lazy {
        try {
            createEncryptedPrefs()
        } catch (_: Exception) {
            // Keystore corruption or OS upgrade: clear and recreate
            context.deleteSharedPreferences(PREFS_NAME)
            createEncryptedPrefs()
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun getUsername(): String? = prefs.getString(KEY_USERNAME, null)

    override fun getSessionCookies(): List<Cookie> {
        prefs.getString(KEY_SESSION_COOKIES, null)?.let { return decodeCookies(it) }
        // Sessions saved by app versions <= 2.2 stored "name=value" pairs only.
        return prefs.getString(KEY_LEGACY_SESSION_COOKIES, null)?.let(::decodeLegacyCookies).orEmpty()
    }

    override fun saveSession(username: String, cookies: List<Cookie>) {
        prefs.edit {
            putString(KEY_USERNAME, username)
            putString(KEY_SESSION_COOKIES, encodeCookies(cookies))
            remove(KEY_LEGACY_SESSION_COOKIES)
        }
    }

    override fun updateSessionCookies(cookies: List<Cookie>) {
        prefs.edit {
            putString(KEY_SESSION_COOKIES, encodeCookies(cookies))
            remove(KEY_LEGACY_SESSION_COOKIES)
        }
    }

    override fun clearSession(keepUsername: Boolean) {
        prefs.edit {
            if (keepUsername) {
                remove(KEY_SESSION_COOKIES)
                remove(KEY_LEGACY_SESSION_COOKIES)
            } else {
                clear()
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "dc_auth_prefs"
        const val KEY_USERNAME = "username"
        const val KEY_LEGACY_SESSION_COOKIES = "session_cookies"
        const val KEY_SESSION_COOKIES = "session_cookies_v2"
    }
}

private val SITE_URL: HttpUrl = "https://www.descente-canyon.com/".toHttpUrl()

/** One `Set-Cookie`-style line per cookie; header values cannot contain line breaks. */
internal fun encodeCookies(cookies: List<Cookie>): String = cookies.joinToString("\n") { it.toString() }

internal fun decodeCookies(encoded: String): List<Cookie> = encoded.lineSequence()
    .filter { it.isNotBlank() }
    .mapNotNull { Cookie.parse(SITE_URL, it) }
    .toList()

internal fun decodeLegacyCookies(encoded: String): List<Cookie> {
    if (encoded.isBlank()) return emptyList()
    return encoded.split('&').mapNotNull { entry ->
        val separatorIndex = entry.indexOf('=')
        if (separatorIndex <= 0) return@mapNotNull null
        val name = URLDecoder.decode(entry.substring(0, separatorIndex), "UTF-8")
        val value = URLDecoder.decode(entry.substring(separatorIndex + 1), "UTF-8")
        runCatching {
            Cookie.Builder()
                .name(name)
                .value(value)
                .domain(SiteCookieJar.TRUSTED_HOST)
                .path("/")
                .secure()
                .build()
        }.getOrNull()
    }
}
