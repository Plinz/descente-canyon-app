package fr.descentecanyon.app.data.network

import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.AppException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OkHttpSiteHttpClientTest {

    private val server = MockWebServer()
    private lateinit var client: OkHttpSiteHttpClient

    @Before
    fun setUp() {
        server.start()
        client = OkHttpSiteHttpClient(OkHttpClient(), Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `get follows redirects and parses the final page`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final.html"))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody("<html><body><h1>Débits</h1></body></html>"),
        )

        val page = client.get(server.url("/start.html").toString())

        assertEquals("Débits", page.document.selectFirst("h1")?.text())
        assertTrue(page.finalUrl.endsWith("/final.html"))
    }

    @Test
    fun `post sends url encoded fields with referer and origin`() = runTest {
        server.enqueue(MockResponse().setBody("<html></html>"))

        client.postForm(
            url = server.url("/form").toString(),
            fields = mapOf("remarque" to "Débit correct & froid", "debit" to "4"),
            referer = "https://www.descente-canyon.com/form",
            origin = "https://www.descente-canyon.com",
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("https://www.descente-canyon.com/form", request.getHeader("Referer"))
        assertEquals("https://www.descente-canyon.com", request.getHeader("Origin"))
        // Spaces are sent as "+" like browsers do (OkHttp 5); PHP decodes both "+" and "%20".
        assertEquals("remarque=D%C3%A9bit+correct+%26+froid&debit=4", request.body.readUtf8())
    }

    @Test
    fun `an immediate refresh header is followed like a redirect`() = runTest {
        // What the site answers to a successful login: an empty page that refreshes to the home page.
        server.enqueue(MockResponse().setHeader("Refresh", "0;url=${server.url("/")}"))
        server.enqueue(MockResponse().setBody("<html><body><h1>Bienvenue</h1></body></html>"))

        val page = client.postForm(server.url("/login").toString(), mapOf("username" to "user"))

        assertEquals("Bienvenue", page.document.selectFirst("h1")?.text())
        assertEquals(server.url("/").toString(), page.finalUrl)
        assertEquals("POST", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `a delayed refresh or a refresh to another host is not followed`() = runTest {
        server.enqueue(MockResponse().setHeader("Refresh", "5;url=/elsewhere").setBody("<html><body><h1>Merci</h1></body></html>"))
        server.enqueue(
            MockResponse().setHeader("Refresh", "0;url=https://example.org/").setBody("<html><body><h1>Merci</h1></body></html>"),
        )

        assertEquals("Merci", client.get(server.url("/delayed").toString()).document.selectFirst("h1")?.text())
        assertEquals("Merci", client.get(server.url("/other-host").toString()).document.selectFirst("h1")?.text())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `http errors are typed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(503))

        assertEquals(AppError.NotFound, failureOf { client.get(server.url("/missing").toString()) })
        assertEquals(AppError.Http(503), failureOf { client.get(server.url("/down").toString()) })
    }

    @Test
    fun `oversized bodies are refused instead of truncated`() {
        val body = "x".repeat(2_000).toResponseBody()

        try {
            body.readBytesWithin(1_000)
            fail("Expected an oversized body to be refused")
        } catch (exception: AppException) {
            assertEquals(AppError.UnexpectedResponse, exception.error)
        }
        assertEquals(2_000, "y".repeat(2_000).toResponseBody().readBytesWithin(2_000).size)
    }

    private suspend fun failureOf(block: suspend () -> Unit): AppError {
        try {
            block()
        } catch (exception: AppException) {
            return exception.error
        }
        throw AssertionError("Expected a failure")
    }
}
