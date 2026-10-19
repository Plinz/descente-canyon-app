package fr.descentecanyon.app.data.remote.scraper

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ForumParserTest {

    @Test
    fun `parse active topics extracts title forum meta and sanitized urls`() {
        val doc = Jsoup.parse(
            """
            <html>
            <body>
              <ul class="topiclist topics">
                <li class="row">
                  <dl class="row-item topic_read_hot">
                    <dt>
                      <a href="./viewtopic.php?f=16&amp;t=28125&amp;sid=abc123" class="topictitle">Baisse des notes Gamchi , Trummel IV</a>
                    </dt>
                    <dd class="posts">34 <dfn>Réponses</dfn></dd>
                    <dd class="views">34624 <dfn>Vues</dfn></dd>
                    <dd class="lastpost">
                      <span><dfn>Dernier message </dfn>par <a href="./memberlist.php?mode=viewprofile&amp;u=3064&amp;sid=abc123" class="username">Max38</a>
                      <a href="./viewtopic.php?f=16&amp;t=28125&amp;p=305248&amp;sid=abc123#p305248" title="Aller au dernier message">
                        <time datetime="2026-04-03T20:20:54+00:00">ven. 03 avr. 2026 22:20</time>
                      </a></span>
                      <br />Publié dans <a href="./viewforum.php?f=16&amp;sid=abc123">SUISSE</a>
                    </dd>
                  </dl>
                </li>
              </ul>
            </body>
            </html>
            """.trimIndent(),
            "https://www.descente-canyon.com/forums/search.php?search_id=active_topics",
        )

        val result = ForumParser.parseActiveTopics(doc)

        assertEquals(1, result.size)
        assertEquals("Baisse des notes Gamchi , Trummel IV", result.first().title)
        assertEquals("SUISSE", result.first().forumName)
        assertEquals(34, result.first().replyCount)
        assertEquals(34624, result.first().viewCount)
        assertEquals("Max38", result.first().lastAuthor)
        assertEquals(
            "https://www.descente-canyon.com/forums/viewtopic.php?f=16&t=28125",
            result.first().topicUrl,
        )
        assertEquals(
            "https://www.descente-canyon.com/forums/viewtopic.php?f=16&t=28125&p=305248#p305248",
            result.first().lastMessageUrl,
        )
        assertNotNull(result.first().lastPostedAtEpochMs)
    }

    @Test
    fun `parse categories keeps the forum index order and the heading of each block`() {
        val doc = Jsoup.parse(
            """
            <html>
            <body>
              <div class="forabg">
                <ul class="topiclist">
                  <li class="header"><dl class="row-item">
                    <dt><div class="list-inner"><a href="./viewforum.php?f=32&amp;sid=abc123">Les forums généralistes</a></div></dt>
                  </dl></li>
                </ul>
                <ul class="topiclist forums">
                  <li class="row"><a href="./viewforum.php?f=3&amp;sid=abc123" class="forumtitle">Matériel, techniques...</a></li>
                  <li class="row"><a href="./viewforum.php?f=21&amp;sid=abc123" class="forumtitle">Petites annonces</a></li>
                </ul>
              </div>
              <div class="forabg">
                <ul class="topiclist">
                  <li class="header"><dl class="row-item">
                    <dt><div class="list-inner"><a href="./viewforum.php?f=33&amp;sid=abc123">Régions &amp; canyons en particulier</a></div></dt>
                  </dl></li>
                </ul>
                <ul class="topiclist forums">
                  <li class="row">
                    <a href="https://www.descente-canyon.com/canyoning/derniers-debits" class="forumtitle">Niveaux d'eau...</a>
                  </li>
                  <li class="row"><a href="./viewforum.php?f=6&amp;sid=abc123" class="forumtitle">FR - Alpes du Sud</a></li>
                  <li class="row"><a href="./viewforum.php?f=16&amp;sid=abc123" class="forumtitle">SUISSE</a></li>
                  <li class="row"><a href="./viewforum.php?f=6&amp;sid=def456" class="forumtitle">FR - Alpes du Sud</a></li>
                </ul>
              </div>
            </body>
            </html>
            """.trimIndent(),
            "https://www.descente-canyon.com/forums/",
        )

        val result = ForumParser.parseCategories(doc)

        assertEquals(listOf(3, 21, 6, 16), result.map { it.forumId })
        assertEquals(
            listOf(
                "Les forums généralistes",
                "Les forums généralistes",
                "Régions & canyons en particulier",
                "Régions & canyons en particulier",
            ),
            result.map { it.section },
        )
        assertEquals("https://www.descente-canyon.com/forums/viewforum.php?f=16", result[3].forumUrl)
    }

    @Test
    fun `parse categories without index blocks keeps the page order`() {
        val doc = Jsoup.parse(
            """
            <html>
            <body>
              <ul class="topiclist forums">
                <li class="row"><a class="forumtitle" href="./viewforum.php?f=16&amp;sid=abc123">SUISSE</a></li>
                <li class="row"><a class="forumtitle" href="./viewforum.php?f=17&amp;sid=abc123">ITALIE</a></li>
                <li class="row"><a class="forumtitle" href="./viewforum.php?f=16&amp;sid=def456">SUISSE</a></li>
              </ul>
            </body>
            </html>
            """.trimIndent(),
            "https://www.descente-canyon.com/forums/",
        )

        val result = ForumParser.parseCategories(doc)

        assertEquals(listOf("SUISSE", "ITALIE"), result.map { it.forumName })
        assertEquals(listOf(null, null), result.map { it.section })
    }
}
