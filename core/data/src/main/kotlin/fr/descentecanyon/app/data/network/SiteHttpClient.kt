package fr.descentecanyon.app.data.network

import fr.descentecanyon.app.domain.coroutines.IoDispatcher
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.AppException
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** A page of descente-canyon.com after redirects. */
data class SitePage(val document: Document, val finalUrl: String)

/**
 * HTTP access to descente-canyon.com. Cookies (and therefore the user session) are handled by
 * [SiteCookieJar]; failures are [AppException]s.
 */
interface SiteHttpClient {
    suspend fun get(url: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): SitePage

    suspend fun postForm(url: String, fields: Map<String, String>, referer: String? = null, origin: String? = null): SitePage

    suspend fun download(url: String, target: File)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}

@Singleton
class OkHttpSiteHttpClient @Inject constructor(
    @param:SiteHttp private val httpClient: OkHttpClient,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SiteHttpClient {

    // At most 3 parallel requests: the site is a community website, not an API.
    private val permits = Semaphore(MAX_PARALLEL_REQUESTS)

    override suspend fun get(url: String, timeoutMs: Long): SitePage = fetchPage(Request.Builder().url(url).get().build(), timeoutMs)

    override suspend fun postForm(url: String, fields: Map<String, String>, referer: String?, origin: String?): SitePage {
        val body = FormBody.Builder()
            .apply { fields.forEach { (name, value) -> add(name, value) } }
            .build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .apply {
                referer?.let { header("Referer", it) }
                origin?.let { header("Origin", it) }
            }
            .build()
        return fetchPage(request, SiteHttpClient.DEFAULT_TIMEOUT_MS)
    }

    override suspend fun download(url: String, target: File) {
        permits.withPermit {
            val response = httpClient.newCall(Request.Builder().url(url).get().build()).await().requireSuccess()
            response.use {
                val body = response.body
                withContext(ioDispatcher) {
                    target.parentFile?.mkdirs()
                    val bytes = body.readBytesWithin(MAX_DOWNLOAD_BYTES)
                    target.writeBytes(bytes)
                }
            }
        }
    }

    private suspend fun fetchPage(request: Request, timeoutMs: Long): SitePage = permits.withPermit {
        var response = execute(request, timeoutMs)
        // The site answers a successful login with an empty page and "Refresh: 0;url=..." instead
        // of an HTTP redirect, which OkHttp does not follow: follow it like a browser does.
        var refreshes = 0
        while (refreshes < MAX_REFRESHES) {
            val target = response.immediateRefreshTarget() ?: break
            response.close()
            response = execute(Request.Builder().url(target).get().build(), timeoutMs)
            refreshes++
        }
        response.use {
            val body = response.body
            withContext(ioDispatcher) {
                val bytes = body.readBytesWithin(MAX_PAGE_BYTES)
                val finalUrl = response.request.url.toString()
                val charset = body.contentType()?.charset()?.name()
                SitePage(
                    document = Jsoup.parse(ByteArrayInputStream(bytes), charset, finalUrl),
                    finalUrl = finalUrl,
                )
            }
        }
    }

    private suspend fun execute(request: Request, timeoutMs: Long): Response {
        val call = httpClient.newCall(request)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        return call.await().requireSuccess()
    }

    /** Target of a "Refresh: 0;url=..." header pointing to the same host, null otherwise. */
    private fun Response.immediateRefreshTarget(): HttpUrl? {
        val target = header("Refresh")?.let { IMMEDIATE_REFRESH.find(it) }?.groupValues?.get(1) ?: return null
        return request.url.resolve(target)?.takeIf { it.host == request.url.host }
    }

    private companion object {
        val IMMEDIATE_REFRESH = Regex("""^\s*0+\s*[;,]\s*url\s*=\s*['"]?([^'"\s]+)""", RegexOption.IGNORE_CASE)
        const val MAX_REFRESHES = 5
        const val MAX_PARALLEL_REQUESTS = 3
        const val MAX_PAGE_BYTES = 16L * 1024 * 1024
        const val MAX_DOWNLOAD_BYTES = 40L * 1024 * 1024
    }
}
