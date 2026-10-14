package fr.descentecanyon.app.data.remote.auth

import org.jsoup.nodes.Document

/** Tells from any page of the site whether the visitor is identified. */
internal object SiteSessionInspector {

    enum class Status { LOGGED_IN, LOGGED_OUT, UNKNOWN }

    /**
     * Anonymous pages carry the login form ("S'identifier") in the right navbar; identified pages
     * show the username (or a logout link) there instead. Anything else, such as a maintenance or
     * error page without the navbar, is [Status.UNKNOWN] and must never be taken for a success.
     */
    fun inspect(document: Document, username: String?): Status {
        val navbar = document.selectFirst("ul.navbar-nav.navbar-right") ?: return Status.UNKNOWN
        val navbarText = navbar.text().replace('’', '\'')
        val showsLoginForm = navbar.selectFirst("input[name=password], form[action*=/login]") != null ||
            navbarText.contains("S'identifier", ignoreCase = true) ||
            navbarText.contains("pas identifié", ignoreCase = true)
        if (showsLoginForm) return Status.LOGGED_OUT

        val showsUsername = !username.isNullOrBlank() && navbarText.contains(username.trim(), ignoreCase = true)
        val showsLogoutLink = navbar.selectFirst("a[href*=logout], a[href*=deconnexion], a[href*=deconnect]") != null
        return if (showsUsername || showsLogoutLink) Status.LOGGED_IN else Status.UNKNOWN
    }
}
