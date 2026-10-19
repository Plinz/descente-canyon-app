package fr.descentecanyon.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app addresses the person using it without a masculine default: sentences are rephrased
 * (a noun, "vous", an epicene word) rather than written with a "·e" ending. These patterns catch
 * the forms that used to slip in; grammatical agreement with things (canyons suivis) is fine.
 */
class InclusiveWordingTest {

    private val forbidden = mapOf(
        "values" to listOf(
            """\b(une fois|bien|être|suis|êtes)\s+(connecté|rentré|averti|inscrit|prêt|arrivé|seul)s?\b""",
            """^Connecté""",
            """\butilisateurs?\b""",
            """\bobservateurs?\b""",
            """\bdes blessés\b""",
        ),
        "values-de" to listOf("""Benutzer""", """\bNutzer\b""", """\bjemanden, der\b"""),
        "values-de-rAT" to listOf("""Benutzer""", """\bNutzer\b""", """\bjemanden, der\b"""),
        "values-it" to listOf(
            """\b(una volta|sono|essere)\s+(connesso|rientrato|avvisato|tornato)\b""",
            """^Connesso""",
            """\b(rientrato bene|bentornato|osservatore|feriti|utenti)\b""",
        ),
        "values-es" to listOf("""\b(conectado|avisado|bienvenido|usuarios?|observador|heridos)\b"""),
    )

    @Test
    fun `no string addresses the user in the masculine`() {
        val offending = forbidden.flatMap { (folder, patterns) ->
            val regexes = patterns.map { Regex("(?U)$it", RegexOption.IGNORE_CASE) }
            strings(folder).filter { (_, text) -> regexes.any { it.containsMatchIn(text) } }
                .map { (name, text) -> "$folder/$name: $text" }
        }

        assertEquals(emptyList<String>(), offending)
    }

    private fun strings(folder: String): List<Pair<String, String>> {
        val xml = File("src/main/res/$folder/strings.xml").readText()
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
    }
}
