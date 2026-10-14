package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.data.remote.dto.ScrapedCanyonDetail
import org.jsoup.nodes.Document

/**
 * Parses a canyon description/topo page: /canyoning/canyon-description/{id}/topo.html
 *
 * Sections are delimited by <h3> headings:
 * Acces, Approche, Descente, Retour, Engagement, Periode/caractere aquatique
 */
internal object DescriptionParser {

    private val accessMarkerRegex = Regex(
        "(^|\\n+)\\s*(aval|amont)\\s*:?\\s*",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    fun parse(doc: Document, canyonId: Int): ScrapedCanyonDetail {
        val sections = mutableMapOf<String, String>()

        // Collect all h3 headings and their following paragraph content
        val h3Elements = doc.select("h3")
        for (h3 in h3Elements) {
            val heading = h3.text().trim().lowercase()
            val content = buildString {
                var sibling = h3.nextElementSibling()
                while (sibling != null && sibling.tagName() != "h3") {
                    if (sibling.tagName() == "p") {
                        if (isNotEmpty()) append("\n\n")
                        append(sibling.wholeText().trim())
                    }
                    sibling = sibling.nextElementSibling()
                }
            }
            if (content.isNotBlank()) {
                sections[heading] = content
            }
        }

        // Split access section into aval/amont
        val accessFull = sections.entries.firstOrNull { it.key.startsWith("acc") }?.value ?: ""
        val (accesAval, accesAmont) = splitAccess(accessFull)

        return ScrapedCanyonDetail(
            id = canyonId,
            accesAval = accesAval,
            accesAmont = accesAmont,
            approche = sections.entries.firstOrNull { it.key.startsWith("approche") }?.value,
            descente = sections.entries.firstOrNull { it.key.startsWith("descente") }?.value,
            retour = sections.entries.firstOrNull { it.key.startsWith("retour") }?.value,
            engagement = sections.entries.firstOrNull { it.key.startsWith("engagement") }?.value,
            periode = sections.entries.firstOrNull {
                it.key.startsWith("p\u00e9riode") || it.key.startsWith("periode")
            }?.value,
        )
    }

    /**
     * The access section contains both "Aval :" and "Amont :" sub-sections in one paragraph.
     */
    private fun splitAccess(accessFull: String): Pair<String?, String?> {
        if (accessFull.isBlank()) return null to null

        val normalizedText = accessFull.replace("\r\n", "\n")
        val matches = accessMarkerRegex.findAll(normalizedText).toList()
        if (matches.isEmpty()) {
            return accessFull.trim().takeIf { it.isNotEmpty() } to null
        }

        val sections = mutableMapOf<String, String>()
        matches.forEachIndexed { index, match ->
            val label = match.groupValues[2].lowercase()
            val startIndex = match.range.last + 1
            val endIndex = matches.getOrNull(index + 1)?.range?.first ?: normalizedText.length
            val content = normalizedText.substring(startIndex, endIndex).trim()
            if (content.isNotEmpty()) {
                sections[label] = content
            }
        }

        return when {
            sections.isEmpty() -> accessFull.trim().takeIf { it.isNotEmpty() } to null
            sections.containsKey("aval") || sections.containsKey("amont") -> {
                sections["aval"] to sections["amont"]
            }
            else -> accessFull.trim().takeIf { it.isNotEmpty() } to null
        }
    }
}
