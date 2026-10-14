package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.mapper.DateParser
import fr.descentecanyon.app.data.remote.dto.ScrapedDebit
import fr.descentecanyon.app.domain.model.RecentDebitCell
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Parses debit (water flow) observation pages.
 */
internal object DebitParser {

    // CSS class -> debit level name mapping
    private val DEBIT_CLASS_MAP = mapOf(
        "debit1" to "CRUE",
        "debit2" to "TRES_GROS",
        "debit3" to "GROS",
        "debit4" to "CORRECT",
        "debit5" to "FILET",
        "debit6" to "SEC",
    )

    /**
     * Parse the debits page for a specific canyon:
     * /canyoning/canyon-debit/{id}/observations.html
     */
    fun parseCanyonDebits(doc: Document, canyonId: Int): List<ScrapedDebit> {
        val results = mutableListOf<ScrapedDebit>()
        val rows = doc.select("table#listedebit tbody tr")

        for (row in rows) {
            // Skip year separator rows (they have colspan)
            if (row.select("td[colspan]").isNotEmpty()) continue

            // Check this is a debit row (class contains debitN)
            val rowClass = row.className()
            val debitLevel = DEBIT_CLASS_MAP.entries
                .firstOrNull { rowClass.contains(it.key) }
                ?.value ?: continue

            val tds = row.select("td")
            if (tds.size < 4) continue

            val dateRaw = tds[0].text().trim()
            val authors = tds[1].html()
                .split(Regex("(?i)<br\\s*/?>"))
                .map { htmlPart -> Jsoup.parse(htmlPart).text().trim() }
                .filter { it.isNotBlank() }
            val observationTitle = tds[2].selectFirst("span")?.attr("title")?.trim()?.lowercase()
            val isDescended = when {
                observationTitle?.contains("parcouru") == true && observationTitle.contains("non") -> false
                observationTitle?.contains("parcouru") == true -> true
                else -> null
            }
            val waterTemperature = tds.getOrNull(4)?.text()?.trim()?.ifBlank { null }
            val airTemperature = tds.getOrNull(5)?.text()?.trim()?.ifBlank { null }

            // Extract remark if exists
            val observationDetails = row.selectFirst("td button.lire")
                ?.id()
                ?.removePrefix("r")
                ?.takeIf { it.isNotBlank() }
                ?.let { remarkId -> parseObservationDetails(doc.selectFirst("tr#tr$remarkId")) }
                .orEmpty()

            val debitObservations = buildDebitObservations(authors, observationDetails)

            for (observation in debitObservations) {
                results.add(
                    ScrapedDebit(
                        canyonId = canyonId,
                        canyonNom = "",
                        date = dateRaw,
                        niveauRaw = debitLevel,
                        auteur = observation.author,
                        isDescended = isDescended,
                        waterTemperature = waterTemperature,
                        airTemperature = airTemperature,
                        commentaire = observation.comment,
                    ),
                )
            }
        }

        return results
    }

    private val GRID_START_DATE = Regex("""\d{1,2}\s+\p{L}+\.?\s+\d{4}""")
    private val GRID_LEVEL_CLASS = Regex("""\bd([1-6])\b""")

    /**
     * Parse the day grid of /canyoning/derniers-debits: one level per canyon and per day over the
     * last ~16 days (the header gives the first day). Icon classes d1..d6 map to CRUE..SEC.
     */
    fun parseLatestDebitGrid(doc: Document): List<RecentDebitCell> {
        val table = doc.selectFirst("table#listedebit") ?: return emptyList()
        val header = table.select("thead th").lastOrNull()?.text().orEmpty()
        val startDate = GRID_START_DATE.find(header)?.value?.let(DateParser::parseToLocalDate) ?: return emptyList()
        val cells = mutableListOf<RecentDebitCell>()
        for (row in table.select("tbody tr")) {
            val tds = row.select("td")
            if (tds.size < 5) continue
            val canyonId = tds[1].selectFirst("a")?.attr("href")
                ?.let { Regex("/canyon/(\\d+)/").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: continue
            for (column in 4 until tds.size) {
                val span = tds[column].selectFirst("span.ic-tint") ?: continue
                val iconLevel = GRID_LEVEL_CLASS.find(span.className())?.groupValues?.get(1)?.toIntOrNull() ?: continue
                cells += RecentDebitCell(
                    canyonId = canyonId,
                    date = startDate.plusDays((column - 4).toLong()),
                    rank = 6 - iconLevel,
                )
            }
        }
        return cells
    }

    /**
     * Parse the global latest debits page: /canyoning/derniers-debits
     */
    fun parseLatestDebits(doc: Document): List<ScrapedDebit> {
        val results = mutableListOf<ScrapedDebit>()
        val rows = doc.select("table#listedebit tbody tr")

        for (row in rows) {
            val tds = row.select("td")
            if (tds.size < 5) continue

            // Column 2: Canyon link
            val canyonLink = tds[1].selectFirst("a") ?: continue
            val canyonNom = canyonLink.text().trim()
            val canyonHref = canyonLink.attr("href")
            val canyonId = Regex("/canyon/(\\d+)/").find(canyonHref)
                ?.groupValues?.get(1)?.toIntOrNull() ?: continue

            // Column 4: Last debit date
            val dateLink = tds[3].selectFirst("a")
            val dateRaw = sanitizeLatestDebitDate(dateLink?.text().orEmpty().ifBlank { tds[3].text() })

            // Determine most recent debit level from the last non-empty day column
            var lastDebitLevel = "INCONNU"
            for (i in (4 until tds.size).reversed()) {
                val span = tds[i].selectFirst("span.ic-tint")
                if (span != null) {
                    val spanClass = span.className()
                    val level = (1..6).firstOrNull { spanClass.contains("d$it") }
                    if (level != null) {
                        lastDebitLevel = DEBIT_CLASS_MAP["debit$level"] ?: "INCONNU"
                        break
                    }
                }
            }

            results.add(
                ScrapedDebit(
                    canyonId = canyonId,
                    canyonNom = canyonNom,
                    date = dateRaw,
                    niveauRaw = lastDebitLevel,
                ),
            )
        }

        return results
    }

    private fun sanitizeLatestDebitDate(raw: String): String = raw
        .replace(Regex("(?i)\\bnon\\s+parcouru\\b"), "")
        .replace(Regex("(?i)\\bparcouru\\b"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun parseObservationDetails(remarkRow: org.jsoup.nodes.Element?): List<DebitObservationDetail> {
        if (remarkRow == null) return emptyList()

        val users = remarkRow.select("div.userc").map { userBlock ->
            userBlock.selectFirst("b")?.text()?.trim().orEmpty()
        }
        val comments = remarkRow.select("p").map { paragraph ->
            paragraph.text().trim().ifBlank { null }
        }

        val count = maxOf(users.size, comments.size)
        return (0 until count).mapNotNull { index ->
            val author = users.getOrNull(index)?.takeIf { it.isNotBlank() }
            val comment = comments.getOrNull(index)
            if (author == null && comment == null) {
                null
            } else {
                DebitObservationDetail(author = author, comment = comment)
            }
        }
    }

    private fun buildDebitObservations(
        authors: List<String>,
        observationDetails: List<DebitObservationDetail>,
    ): List<DebitObservationDetail> {
        if (authors.isEmpty() && observationDetails.isEmpty()) {
            return listOf(DebitObservationDetail())
        }
        if (authors.isEmpty()) {
            return observationDetails
        }

        return authors.map { author ->
            val matchingDetail = observationDetails.firstOrNull { detail -> detail.author == author }
            DebitObservationDetail(
                author = author,
                comment = matchingDetail?.comment,
            )
        }
    }

    private data class DebitObservationDetail(val author: String? = null, val comment: String? = null)
}
