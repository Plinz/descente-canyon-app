package fr.descentecanyon.app.data.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteCookieJarTest {

    private val siteUrl = "https://www.descente-canyon.com/canyoning".toHttpUrl()

    @Test
    fun `only https descente-canyon hosts are trusted`() {
        assertTrue(SiteCookieJar.isTrustedUrl("https://www.descente-canyon.com/canyoning".toHttpUrl()))
        assertTrue(SiteCookieJar.isTrustedUrl("https://descente-canyon.com/login".toHttpUrl()))
        assertTrue(SiteCookieJar.isTrustedUrl("https://app.descente-canyon.com/x".toHttpUrl()))
        assertTrue(SiteCookieJar.isTrustedUrl("https://WWW.DESCENTE-CANYON.COM/x".toHttpUrl()))

        assertFalse(SiteCookieJar.isTrustedUrl("http://www.descente-canyon.com/canyoning".toHttpUrl()))
        assertFalse(SiteCookieJar.isTrustedUrl("https://evil.example.com/x".toHttpUrl()))
        assertFalse(SiteCookieJar.isTrustedUrl("https://descente-canyon.com.evil.example/x".toHttpUrl()))
        assertFalse(SiteCookieJar.isTrustedUrl("https://example.com/descente-canyon.com/x".toHttpUrl()))
    }

    @Test
    fun `cookies set by the site are sent back to it only`() {
        val jar = SiteCookieJar()
        jar.saveFromResponse(siteUrl, listOf(cookie("sid", "abc")))

        assertEquals(listOf("sid"), jar.loadForRequest(siteUrl).map { it.name })
        assertTrue(jar.loadForRequest("http://www.descente-canyon.com/canyoning".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://evil.example.com/".toHttpUrl()).isEmpty())
    }

    @Test
    fun `cookies from other hosts are ignored`() {
        val jar = SiteCookieJar()
        val otherHost = "https://evil.example.com/".toHttpUrl()
        jar.saveFromResponse(otherHost, listOf(Cookie.parse(otherHost, "sid=stolen")!!))

        assertTrue(jar.snapshot().isEmpty())
    }

    @Test
    fun `a cookie is replaced by a newer value and removed when expired`() {
        val jar = SiteCookieJar()
        var notified: List<Cookie>? = null
        jar.onCookiesChanged = { notified = it }

        jar.saveFromResponse(siteUrl, listOf(cookie("sid", "old")))
        jar.saveFromResponse(siteUrl, listOf(cookie("sid", "new")))
        assertEquals(listOf("new"), jar.snapshot().map { it.value })
        assertEquals(listOf("new"), notified?.map { it.value })

        jar.saveFromResponse(siteUrl, listOf(Cookie.parse(siteUrl, "sid=deleted; Max-Age=0")!!))
        assertTrue(jar.snapshot().isEmpty())
    }

    @Test
    fun `clear forgets every cookie`() {
        val jar = SiteCookieJar()
        jar.replaceAll(listOf(cookie("sid", "abc")))
        jar.clear()

        assertTrue(jar.loadForRequest(siteUrl).isEmpty())
    }

    private fun cookie(name: String, value: String): Cookie = Cookie.parse(siteUrl, "$name=$value; Path=/; Secure")!!
}
