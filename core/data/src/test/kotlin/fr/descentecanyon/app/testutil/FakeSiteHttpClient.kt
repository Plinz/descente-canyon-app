package fr.descentecanyon.app.testutil

import fr.descentecanyon.app.data.network.SiteHttpClient
import fr.descentecanyon.app.data.network.SitePage
import java.io.File
import org.jsoup.Jsoup

/** Scripted [SiteHttpClient]: each GET/POST URL answers the queued pages in order. */
class FakeSiteHttpClient : SiteHttpClient {

    data class Post(val url: String, val fields: Map<String, String>, val referer: String?, val origin: String?)

    private val getAnswers = mutableMapOf<String, ArrayDeque<() -> SitePage>>()
    private val postAnswers = mutableMapOf<String, ArrayDeque<() -> SitePage>>()
    val gets = mutableListOf<String>()
    val posts = mutableListOf<Post>()

    fun onGet(url: String, html: String, finalUrl: String = url) = apply {
        getAnswers.getOrPut(url) { ArrayDeque() }.addLast { page(html, finalUrl) }
    }

    fun onGetFailure(url: String, error: Throwable) = apply {
        getAnswers.getOrPut(url) { ArrayDeque() }.addLast { throw error }
    }

    fun onPost(url: String, html: String, finalUrl: String = url) = apply {
        postAnswers.getOrPut(url) { ArrayDeque() }.addLast { page(html, finalUrl) }
    }

    override suspend fun get(url: String, timeoutMs: Long): SitePage {
        gets += url
        return next(getAnswers, "GET", url)
    }

    override suspend fun postForm(url: String, fields: Map<String, String>, referer: String?, origin: String?): SitePage {
        posts += Post(url, fields, referer, origin)
        return next(postAnswers, "POST", url)
    }

    override suspend fun download(url: String, target: File): Unit = throw UnsupportedOperationException("download $url")

    private fun next(answers: Map<String, ArrayDeque<() -> SitePage>>, method: String, url: String): SitePage {
        val queue = answers[url] ?: throw AssertionError("Unexpected $method $url")
        val answer = if (queue.size > 1) queue.removeFirst() else queue.firstOrNull() ?: throw AssertionError("No answer for $method $url")
        return answer()
    }

    private fun page(html: String, finalUrl: String) = SitePage(Jsoup.parse(html, finalUrl), finalUrl)
}

/** Minimal pages of the site with its navigation bar, logged in or anonymous. */
object SitePages {
    fun loggedIn(username: String, body: String = "") = """
        <html><body>
          <ul class="nav navbar-nav navbar-right">
            <li class="dropdown"><a href="#" class="dropdown-toggle">Mon compte <b class="caret"></b></a>
              <ul class="dropdown-menu">
                <li class="dropdown-header">Bienvenue <b>$username</b> !</li>
                <li><a href="/perso">Mon compte</a></li>
                <li><a href="/logout?redirect=/">Déconnexion</a></li>
              </ul>
            </li>
          </ul>
          $body
        </body></html>
    """.trimIndent()

    fun anonymous(body: String = "") = """
        <html><body>
          <ul class="nav navbar-nav navbar-right">
            <li class="dropdown"><a href="#" class="dropdown-toggle">S'identifier <b class="caret"></b></a>
              <ul class="dropdown-menu">
                <li class="dropdown-header">Vous n'êtes pas identifié.</li>
                <li><form class="navbar-form" method="post" action="/login?redirect=/">
                  <input type="text" name="username" /><input type="password" name="password" />
                </form></li>
              </ul>
            </li>
          </ul>
          $body
        </body></html>
    """.trimIndent()

    /** An error or maintenance page served without the site layout. */
    const val NOT_A_SITE_PAGE = "<html><body><h1>Service temporairement indisponible</h1></body></html>"
}
