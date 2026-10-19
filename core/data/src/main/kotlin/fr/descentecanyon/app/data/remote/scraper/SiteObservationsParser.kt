package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.mapper.DateParser
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.domain.model.SiteObservation
import java.time.LocalDate
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Reads "Ma liste de courses" (/perso/observations/all): one row per report of the member (date,
 * canyon, area, descended or not, flow, temperatures) and a hidden row with the remarks, the
 * personal comment and the link to the canyon sheet.
 */
internal object SiteObservationsParser {

    private val LEVEL_BY_CLASS = mapOf(
        "debit1" to NiveauDebit.CRUE,
        "debit2" to NiveauDebit.TRES_GROS,
        "debit3" to NiveauDebit.GROS,
        "debit4" to NiveauDebit.CORRECT,
        "debit5" to NiveauDebit.FILET,
        "debit6" to NiveauDebit.SEC,
    )
    private val LEVEL_BY_TITLE = NiveauDebit.entries.associateBy { it.label.lowercase() }
    private val CANYON_LINK = Regex("""/canyoning/canyon/(\d+)/""")
    private val OBSERVATION_ID = Regex("""(\d+)$""")

    /** Whether the page is the list (possibly empty) rather than a login form or another page. */
    fun isObservationsPage(document: Document): Boolean =
        document.selectFirst("table#listedebit") != null || document.selectFirst("ol.breadcrumb a[href=/perso]") != null

    fun parse(document: Document, today: LocalDate = LocalDate.now()): List<SiteObservation> {
        val remarks = document.select("tr.remarque[id^=tr]").associateBy { it.id().removePrefix("tr") }
        return document.select("table#listedebit tbody tr").mapNotNull { row ->
            if (row.hasClass("remarque")) return@mapNotNull null
            val cells = row.children().filter { it.normalName() == "td" }
            if (cells.size < 5) return@mapNotNull null
            val id =
                row.selectFirst("button[id^=r]")?.id()?.let { OBSERVATION_ID.find(it)?.value?.toLongOrNull() } ?: return@mapNotNull null
            val date = DateParser.parseToIsoString(cells[0].text(), today)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: return@mapNotNull null
            val details = remarks[id.toString()]
            val descendedTitle = cells[3].selectFirst("span[title]")?.attr("title")?.lowercase().orEmpty()
            SiteObservation(
                id = id,
                canyonId = details?.selectFirst("a[href*=/canyoning/canyon/]")?.attr("href")
                    ?.let { CANYON_LINK.find(it)?.groupValues?.get(1)?.toIntOrNull() },
                canyonName = cells[1].text().trim(),
                date = date,
                isDescended = descendedTitle.contains("parcouru") && !descendedTitle.contains("non"),
                debitLevel = row.classNames().firstNotNullOfOrNull { LEVEL_BY_CLASS[it] }
                    ?: LEVEL_BY_TITLE[cells[4].attr("title").trim().lowercase()],
                waterTemperature = cells.getOrNull(5)?.text()?.trim()?.ifEmpty { null },
                airTemperature = cells.getOrNull(6)?.text()?.trim()?.ifEmpty { null },
                publicRemark = details?.labelledText("Remarque publique"),
                personalComment = details?.labelledText("Commentaire personnel"),
            )
        }
    }

    /** Text of the paragraph "<label> : <span>…</span>", line breaks kept. */
    private fun Element.labelledText(label: String): String? {
        val paragraph = select("p").firstOrNull { it.ownText().trim().startsWith(label, ignoreCase = true) } ?: return null
        val span = paragraph.selectFirst("span") ?: return null
        // wholeText() keeps the line breaks of the text and turns each <br> into one.
        return span.wholeText()
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
            .ifEmpty { null }
    }
}
