package fr.descentecanyon.app.data.network

import fr.descentecanyon.app.domain.coroutines.IoDispatcher
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.AppException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** HTTP access to JSON / file APIs (Open-Meteo, EDF, Météo-France and MeteoSwiss open data). */
interface ApiHttpClient {
    suspend fun <T> getJson(url: HttpUrl, deserializer: DeserializationStrategy<T>): T

    suspend fun getBytes(url: HttpUrl, maxBytes: Long = DEFAULT_MAX_BYTES): ByteArray

    /** Streams the body to [read]; the connection is closed as soon as [read] returns. */
    suspend fun <T> getStream(url: HttpUrl, read: (InputStream) -> T): T

    companion object {
        const val DEFAULT_MAX_BYTES = 32L * 1024 * 1024
    }
}

@Singleton
class OkHttpApiHttpClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ApiHttpClient {

    override suspend fun <T> getJson(url: HttpUrl, deserializer: DeserializationStrategy<T>): T {
        val text = getBytes(url, MAX_JSON_BYTES).decodeToString()
        return withContext(ioDispatcher) {
            try {
                json.decodeFromString(deserializer, text)
            } catch (exception: SerializationException) {
                throw AppException(AppError.UnexpectedResponse, exception)
            } catch (exception: IllegalArgumentException) {
                throw AppException(AppError.UnexpectedResponse, exception)
            }
        }
    }

    override suspend fun getBytes(url: HttpUrl, maxBytes: Long): ByteArray {
        val response = newCall(url).await().requireSuccess()
        return response.use {
            val body = response.body ?: throw AppException(AppError.UnexpectedResponse)
            withContext(ioDispatcher) { body.readBytesWithin(maxBytes) }
        }
    }

    override suspend fun <T> getStream(url: HttpUrl, read: (InputStream) -> T): T {
        val response = newCall(url).await().requireSuccess()
        return response.use {
            val body = response.body ?: throw AppException(AppError.UnexpectedResponse)
            withContext(ioDispatcher) { body.byteStream().use(read) }
        }
    }

    private fun newCall(url: HttpUrl) = httpClient.newCall(Request.Builder().url(url).get().build()).apply {
        timeout().timeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val MAX_JSON_BYTES = 8L * 1024 * 1024
        const val CALL_TIMEOUT_SECONDS = 90L
    }
}
