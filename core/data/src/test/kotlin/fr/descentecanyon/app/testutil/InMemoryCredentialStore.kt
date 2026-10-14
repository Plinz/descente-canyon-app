package fr.descentecanyon.app.testutil

import fr.descentecanyon.app.data.remote.auth.CredentialStore
import okhttp3.Cookie

class InMemoryCredentialStore(var storedUsername: String? = null, var storedCookies: List<Cookie> = emptyList()) : CredentialStore {
    override fun getUsername(): String? = storedUsername

    override fun getSessionCookies(): List<Cookie> = storedCookies

    override fun saveSession(username: String, cookies: List<Cookie>) {
        storedUsername = username
        storedCookies = cookies
    }

    override fun updateSessionCookies(cookies: List<Cookie>) {
        storedCookies = cookies
    }

    override fun clearSession(keepUsername: Boolean) {
        storedCookies = emptyList()
        if (!keepUsername) storedUsername = null
    }
}
