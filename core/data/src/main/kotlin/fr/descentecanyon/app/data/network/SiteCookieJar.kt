package fr.descentecanyon.app.data.network

import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Cookie store of the descente-canyon.com client.
 *
 * Cookies are only accepted from, and only sent to, descente-canyon.com (and its subdomains) over
 * HTTPS, so the session can never leak to another host or travel in clear text. The jar itself is
 * in memory; [SessionManager][fr.descentecanyon.app.data.remote.auth.SessionManager] persists it
 * while a session is open through [onCookiesChanged].
 */
@Singleton
class SiteCookieJar @Inject constructor() : CookieJar {

    private val lock = Any()
    private var cookies: List<Cookie> = emptyList()

    /** Called with the full cookie list every time the site changes a cookie. */
    @Volatile
    var onCookiesChanged: ((List<Cookie>) -> Unit)? = null

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty() || !isTrustedUrl(url)) return
        val updated = synchronized(lock) {
            val replaced = this.cookies.filterNot { existing -> cookies.any { it.sameIdentityAs(existing) } }
            this.cookies = (replaced + cookies).withoutExpired()
            this.cookies
        }
        onCookiesChanged?.invoke(updated)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!isTrustedUrl(url)) return emptyList()
        val now = System.currentTimeMillis()
        return synchronized(lock) { cookies.filter { it.expiresAt > now && it.matches(url) } }
    }

    fun snapshot(): List<Cookie> = synchronized(lock) { cookies.withoutExpired() }

    fun replaceAll(cookies: List<Cookie>) {
        synchronized(lock) { this.cookies = cookies.withoutExpired() }
    }

    fun clear() {
        synchronized(lock) { cookies = emptyList() }
    }

    private fun Cookie.sameIdentityAs(other: Cookie): Boolean = name == other.name && domain == other.domain && path == other.path

    private fun List<Cookie>.withoutExpired(): List<Cookie> {
        val now = System.currentTimeMillis()
        return filter { it.expiresAt > now }
    }

    companion object {
        const val TRUSTED_HOST = "descente-canyon.com"

        fun isTrustedUrl(url: HttpUrl): Boolean {
            val host = url.host.lowercase()
            return url.isHttps && (host == TRUSTED_HOST || host.endsWith(".$TRUSTED_HOST"))
        }
    }
}
