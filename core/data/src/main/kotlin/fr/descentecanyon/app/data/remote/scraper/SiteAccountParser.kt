package fr.descentecanyon.app.data.remote.scraper

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * The account page of the logged-in member (`/perso`: account name, e-mail address, password).
 * Only an address the page gives as the member's is taken: the value of an e-mail field, or the
 * text next to a label naming the e-mail; never an address of the site's header or footer.
 */
internal object SiteAccountParser {

    private val EMAIL = Regex("""[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val LABEL = Regex("""(?i)\b(e-?mail|courriel|adresse électronique)\b""")

    fun email(document: Document): String? {
        val body = document.body() ?: return null
        val page = body.clone().apply { select("header, footer, nav, .navbar, script, style").remove() }
        page.select("input[type=email], input[name*=mail]").forEach { input ->
            EMAIL.matchEntire(input.attr("value").trim())?.let { return it.value }
        }
        page.select("label, th, dt, td, strong, b, span, p, li, div").forEach { element ->
            if (!LABEL.containsMatchIn(element.ownText())) return@forEach
            addressNear(element)?.let { return it }
        }
        return null
    }

    /** "E-mail : x@y.fr" in the label itself, else in the element that follows it, else in its row. */
    private fun addressNear(label: Element): String? {
        EMAIL.find(label.ownText())?.let { return it.value }
        label.nextElementSibling()?.let { next -> EMAIL.find(next.text())?.let { return it.value } }
        val row = label.parent()?.takeIf { it.tagName() in ROW_TAGS } ?: return null
        return EMAIL.find(row.text())?.value
    }

    private val ROW_TAGS = setOf("tr", "dl", "li", "p", "div", "label")
}
