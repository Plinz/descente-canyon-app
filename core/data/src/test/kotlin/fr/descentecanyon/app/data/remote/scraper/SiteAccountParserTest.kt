package fr.descentecanyon.app.data.remote.scraper

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiteAccountParserTest {

    private fun page(content: String) = Jsoup.parse(
        """
        <html><body>
        <nav class="navbar"><ul class="navbar-nav navbar-right"><li>Membre</li></ul></nav>
        <div class="container">$content</div>
        <footer>Contact : webmaster@descente-canyon.com</footer>
        </body></html>
        """.trimIndent(),
    )

    @Test
    fun `the address of an e-mail field is the member's`() {
        val document =
            page(
                """<form><label>Identifiant</label><input name="login" value="Membre"><label>E-mail</label><input type="email" name="mail" value=" membre@example.org "></form>""",
            )

        assertEquals("membre@example.org", SiteAccountParser.email(document))
    }

    @Test
    fun `the address next to its label is the member's`() {
        val table =
            page(
                """<table><tr><th>Identifiant</th><td>Membre</td></tr><tr><th>Adresse e-mail</th><td>membre@example.org</td></tr></table>""",
            )
        val inline = page("""<p><strong>Votre e-mail :</strong> membre.test+dc@example.fr</p>""")
        val sameText = page("""<div>Courriel : membre@example.org</div>""")

        assertEquals("membre@example.org", SiteAccountParser.email(table))
        assertEquals("membre.test+dc@example.fr", SiteAccountParser.email(inline))
        assertEquals("membre@example.org", SiteAccountParser.email(sameText))
    }

    @Test
    fun `an address of the site's header or footer is never taken for the member's`() {
        assertNull(SiteAccountParser.email(page("""<p>Identifiant : Membre</p><p>Mot de passe : ********</p>""")))
    }
}
