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
    fun `parse categories extracts all forum sections uniquely`() {
        val doc = Jsoup.parse(
            """
            <html>
            <body>
              <ul class="topiclist forums">
                <li class="row">
                  <a class="forumtitle" href="./viewforum.php?f=16&amp;sid=abc123">SUISSE</a>
                </li>
                <li class="row">
                  <a class="forumtitle" href="./viewforum.php?f=17&amp;sid=abc123">ITALIE</a>
                </li>
                <li class="row">
                  <a class="forumtitle" href="./viewforum.php?f=16&amp;sid=def456">SUISSE</a>
                </li>
              </ul>
            </body>
            </html>
            """.trimIndent(),
            "https://www.descente-canyon.com/forums/",
        )

        val result = ForumParser.parseCategories(doc)

        assertEquals(2, result.size)
        assertEquals("ITALIE", result[0].forumName)
        assertEquals("SUISSE", result[1].forumName)
        assertEquals(16, result[1].forumId)
        assertEquals(
            "https://www.descente-canyon.com/forums/viewforum.php?f=16",
            result[1].forumUrl,
        )
    }
}
