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
import okhttp3.OkHttpClient
import okhttp3.Request
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
        val call = httpClient.newCall(request)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        val response = call.await().requireSuccess()
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

    private companion object {
        const val MAX_PARALLEL_REQUESTS = 3
        const val MAX_PAGE_BYTES = 16L * 1024 * 1024
        const val MAX_DOWNLOAD_BYTES = 40L * 1024 * 1024
    }
}
