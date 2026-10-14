package fr.descentecanyon.app.data.repository

import java.text.Normalizer

/**
 * Detects high-flow reports that are only messages (fire-risk closures, works, parking rules...).
 * Port of `scripts/debit_model/label_quality.py`: such reports are excluded from the canyon history
 * used by the debit model, exactly as they are excluded from its training data.
 */
object DebitReportQuality {

    private val EXPLICIT = listOf(
        Regex("""\bsans rapport avec (?:le |les )?debits?\b"""),
        Regex("""\bsans lien avec (?:le |les )?debits?\b"""),
    )

    private val INFO = listOf(
        Regex("""\binformations?\b"""),
        Regex("""\binfos?\b"""),
        Regex("""\bincendies?\b"""),
        Regex("""\binterdi(?:t|te|ts|tes|ction|ctions)\b"""),
        Regex("""\barretes?\b"""),
        Regex("""\btravaux\b"""),
        Regex("""\bchantiers?\b"""),
        Regex("""\breequip\w*"""),
        Regex("""\bstationnement\b"""),
        Regex("""\bparkings?\b"""),
        Regex("""\bproprietaires?\b"""),
        Regex("""\bfermetures?\b"""),
        Regex("""\bfermee?s?\b"""),
        Regex("""\bsurfrequentation\b"""),
        Regex("""\bprevention\b"""),
        Regex("""\bbaignade\b"""),
        Regex("""\bcirculation\b"""),
    )

    private val HYDRO = listOf(
        Regex("""\bdebits?\b"""),
        Regex("""\beaux?\b"""),
        Regex("""\bcrues?\b"""),
        Regex("""\bpluies?\b"""),
        Regex("""\borages?\w*"""),
        Regex("""\bg ?d\b"""),
        Regex("""\btgd\b"""),
        Regex("""\bdc\b"""),
        Regex("""\bm3\b"""),
        Regex("""\bpousse\w*"""),
        Regex("""\bbrass\w*"""),
        Regex("""\bfonte\b"""),
        Regex("""\bflotte\b"""),
        Regex("""\bchocolat\b"""),
        Regex("""\bcaudal\b"""),
        Regex("""\bportata\b"""),
        Regex("""\bwater\b"""),
        Regex("""\bflow\b"""),
        Regex("""\bechelle\b"""),
        Regex("""\bimpraticable\w*"""),
        Regex("""\becume\w*"""),
        Regex("""\bprecipitations?\b"""),
        Regex("""\bembruns?\b"""),
        Regex("""\bniveaux?\b"""),
        Regex("""\bcharge\w*"""),
        Regex("""\bacqua\b"""),
        Regex("""\bfurieu\w*"""),
        Regex("""\bdegel\w*"""),
        Regex("""\bfond(?:re|u|ue|ait)\b"""),
        Regex("""\bneige\b"""),
        Regex("""\bl s\b"""),
        Regex("""\bcascades?\b"""),
        Regex("""\bmonte\b"""),
    )

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")

    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        return decomposed.replace(COMBINING_MARKS, "").replace(NON_ALPHANUMERIC, " ").trim()
    }

    fun isMessageOnlyHighReport(rank: Int, isDescended: Boolean?, comment: String?): Boolean {
        if (rank < HIGH_RANK || isDescended != false) return false
        val text = normalize(comment)
        if (text.isEmpty()) return false
        if (EXPLICIT.any { it.containsMatchIn(text) }) return true
        return INFO.any { it.containsMatchIn(text) } && HYDRO.none { it.containsMatchIn(text) }
    }

    private const val HIGH_RANK = 3
}
