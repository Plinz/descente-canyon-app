package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.remote.dto.ScrapedPhoto
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Parses canyon photos from: /canyoning/canyon-photo/{id}/photographie.html
 * Photos are <a class="fancybox"> elements with data attributes.
 */
internal object PhotoParser {

    fun parse(doc: Document, canyonId: Int): List<ScrapedPhoto> {
        return doc.select("a.fancybox").mapNotNull { link ->
            val fullUrl = link.attr("abs:href").ifBlank { link.attr("href") }
            if (fullUrl.isBlank()) return@mapNotNull null

            val thumbImg = link.selectFirst("img")
            val thumbnailUrl = thumbImg?.attr("abs:src")
                ?: thumbImg?.attr("src")

            val title = link.attr("title").ifBlank { null }
            val auteur = link.attr("data-copy").ifBlank { null }
            val datePrise = link.attr("data-dateprise").ifBlank { null }

            // data-username contains HTML-encoded user card; extract plain name
            val usernameHtml = link.attr("data-username")
            val username = if (usernameHtml.isNotBlank()) {
                Jsoup.parse(usernameHtml).selectFirst("b")?.text() ?: auteur
            } else {
                auteur
            }

            ScrapedPhoto(
                canyonId = canyonId,
                url = fullUrl,
                thumbnailUrl = thumbnailUrl,
                auteur = username,
                description = title,
                datePrise = datePrise,
            )
        }
    }
}
