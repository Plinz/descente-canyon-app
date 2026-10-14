package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.remote.auth.SessionManager
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Values the browser would send for the form as displayed (submit buttons excluded). */
internal fun Element.readFormDefaults(): Map<String, String> {
    return select("input[name], textarea[name], select[name]")
        .mapNotNull { element ->
            val name = element.attr("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = element.attr("type")
            if (type == "button" || type == "submit" || type == "reset") return@mapNotNull null
            val value = when (element.tagName()) {
                "textarea" -> element.text()
                "select" -> element.selectFirst("option[selected]")?.attr("value").orEmpty()
                else -> element.attr("value")
            }
            name to value
        }
        .toMap()
}

internal fun Element.hasFormControl(name: String): Boolean = getElementsByAttributeValue("name", name).isNotEmpty()

/** Error text the site displays above a refused form, if any. */
internal fun Document.extractSiteErrorMessage(): String? = select(".alert-danger, .alert-error")
    .joinToString(" ") { it.text() }
    .trim()
    .takeIf { it.isNotBlank() }

/** Whether the page is a regular page of the site (with its navigation bar), not an error or maintenance page. */
internal fun Document.isRegularSitePage(): Boolean = selectFirst("ul.navbar-nav.navbar-right") != null

/**
 * The site served an anonymous page for an action that needs an account: the saved session has
 * expired if there was one, otherwise the user has to log in.
 */
internal fun SessionManager.failAsAnonymous(): Nothing {
    if (isLoggedIn) {
        markExpired()
        appError(AppError.SessionExpired)
    }
    appError(AppError.AuthenticationRequired)
}
