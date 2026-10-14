package fr.descentecanyon.app.data.remote.auth

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieCodecTest {

    private val siteUrl = "https://www.descente-canyon.com/".toHttpUrl()

    @Test
    fun `cookies survive an encode decode round trip`() {
        val cookies = listOf(
            Cookie.parse(siteUrl, "phpbb3_hymrt_u=9696; Path=/; Domain=descente-canyon.com; Secure; HttpOnly")!!,
            Cookie.parse(siteUrl, "dc_session=abc; Path=/")!!,
        )

        val decoded = decodeCookies(encodeCookies(cookies))

        assertEquals(cookies.map { it.name to it.value }, decoded.map { it.name to it.value })
        assertTrue(decoded.all { it.matches(siteUrl) })
    }

    @Test
    fun `sessions saved by previous versions are still readable`() {
        val decoded = decodeLegacyCookies("phpbb3_hymrt_u=9696&dc_session=a%26b")

        assertEquals(listOf("phpbb3_hymrt_u" to "9696", "dc_session" to "a&b"), decoded.map { it.name to it.value })
        assertTrue(decoded.all { it.matches(siteUrl) && it.secure })
    }

    @Test
    fun `malformed legacy entries are skipped`() {
        assertEquals(listOf("ok"), decodeLegacyCookies("=x&novalue&ok=1").map { it.name })
        assertTrue(decodeLegacyCookies("").isEmpty())
    }
}
