package fr.descentecanyon.app.data.remote.backend

import fr.descentecanyon.app.data.BackendConfig
import fr.descentecanyon.app.data.network.await
import fr.descentecanyon.app.data.network.readBytesWithin
import fr.descentecanyon.app.domain.coroutines.IoDispatcher
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.AppException
import fr.descentecanyon.app.domain.error.appError
import fr.descentecanyon.app.domain.model.FeedbackRefusal
import fr.descentecanyon.app.domain.model.ProposalRefusal
import fr.descentecanyon.app.domain.model.TrackRejection
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * HTTP access to the app's server. Plain reads (manifest, tracks of a canyon) carry no identifier;
 * only contributions (tracks, votes, proposed canyons, messages of the idea box) and the lists of
 * this installation's own proposals and messages send the random key of the installation. The shared OkHttp cache revalidates
 * the manifest and the track lists with their ETag, so an unchanged file costs a 304.
 */
@Singleton
class BackendClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val config: BackendConfig,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val baseUrl: HttpUrl? = config.baseUrl.takeIf { config.isConfigured }?.trimEnd('/')?.toHttpUrlOrNull()

    suspend fun fetchManifest(): String = execute(get(url("v1/manifest.json"))) { response ->
        response.body.readBytesWithin(MAX_MANIFEST_BYTES).decodeToString()
    }

    /**
     * Streams a published file to [target] and returns the SHA-256 of what was written. Data files
     * never go to the HTTP cache: they are large and kept on disk by the caller.
     */
    suspend fun download(path: String, target: File, maxBytes: Long): String {
        val request = Request.Builder()
            .url(url("v1/$path"))
            .cacheControl(CacheControl.Builder().noStore().build())
            .get()
            .build()
        return execute(request, callTimeoutSeconds = DOWNLOAD_TIMEOUT_SECONDS) { response ->
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            response.body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > maxBytes) throw AppException(AppError.UnexpectedResponse, IOException("File larger than announced"))
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /** A small published file, read whole (never cached: catalog bundles are applied once). */
    suspend fun fetchFile(path: String, maxBytes: Long): ByteArray {
        val request = Request.Builder()
            .url(url("v1/$path"))
            .cacheControl(CacheControl.Builder().noStore().build())
            .get()
            .build()
        return execute(request) { response -> response.body.readBytesWithin(maxBytes) }
    }

    suspend fun listTracks(canyonId: Int): TrackListDto = execute(get(url("v1/canyons/$canyonId/tracks"))) { response ->
        decode(response, TrackListDto.serializer())
    }

    suspend fun uploadTrack(canyonId: Int, role: String, gpx: ByteArray, installKey: String): TrackDto {
        val url = url("v1/canyons/$canyonId/tracks").newBuilder().addQueryParameter("role", role).build()
        val request = Request.Builder()
            .url(url)
            .header(INSTALL_KEY_HEADER, installKey)
            .post(gpx.toRequestBody(GPX_MEDIA_TYPE))
            .build()
        return execute(request) { response -> decode(response, TrackDto.serializer()) }
    }

    suspend fun vote(trackId: String, value: Int, installKey: String): TrackDto {
        val body = json.encodeToString(VoteRequestDto.serializer(), VoteRequestDto(value)).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url("v1/tracks/$trackId/vote"))
            .header(INSTALL_KEY_HEADER, installKey)
            .put(body)
            .build()
        return execute(request) { response -> decode(response, TrackDto.serializer()) }
    }

    suspend fun deleteTrack(trackId: String, installKey: String) {
        val request = Request.Builder()
            .url(url("v1/tracks/$trackId"))
            .header(INSTALL_KEY_HEADER, installKey)
            .delete()
            .build()
        execute(request) { }
    }

    suspend fun submitProposal(request: ProposalRequestDto, installKey: String): ProposalDto {
        val body = json.encodeToString(ProposalRequestDto.serializer(), request).toRequestBody(JSON_MEDIA_TYPE)
        val call = Request.Builder()
            .url(url("v1/canyon-proposals"))
            .header(INSTALL_KEY_HEADER, installKey)
            .post(body)
            .build()
        return execute(call, mapError = ::proposalErrorOf) { response -> decode(response, ProposalDto.serializer()) }
    }

    suspend fun myProposals(installKey: String): ProposalListDto {
        val request = Request.Builder()
            .url(url("v1/canyon-proposals/mine"))
            .header(INSTALL_KEY_HEADER, installKey)
            .cacheControl(CacheControl.Builder().noStore().build())
            .get()
            .build()
        return execute(request, mapError = ::proposalErrorOf) { response -> decode(response, ProposalListDto.serializer()) }
    }

    suspend fun withdrawProposal(proposalId: String, installKey: String) {
        val request = Request.Builder()
            .url(url("v1/canyon-proposals").newBuilder().addPathSegment(proposalId).build())
            .header(INSTALL_KEY_HEADER, installKey)
            .delete()
            .build()
        execute(request, mapError = ::proposalErrorOf) { }
    }

    suspend fun submitFeedback(request: FeedbackRequestDto, installKey: String): FeedbackDto {
        val body = json.encodeToString(FeedbackRequestDto.serializer(), request).toRequestBody(JSON_MEDIA_TYPE)
        val call = Request.Builder()
            .url(url("v1/feedback"))
            .header(INSTALL_KEY_HEADER, installKey)
            .post(body)
            .build()
        return execute(call, mapError = ::feedbackErrorOf) { response -> decode(response, FeedbackDto.serializer()) }
    }

    suspend fun myFeedback(installKey: String): FeedbackListDto {
        val request = Request.Builder()
            .url(url("v1/feedback/mine"))
            .header(INSTALL_KEY_HEADER, installKey)
            .cacheControl(CacheControl.Builder().noStore().build())
            .get()
            .build()
        return execute(request, mapError = ::feedbackErrorOf) { response -> decode(response, FeedbackListDto.serializer()) }
    }

    suspend fun deleteFeedback(feedbackId: String, installKey: String) {
        val request = Request.Builder()
            .url(url("v1/feedback").newBuilder().addPathSegment(feedbackId).build())
            .header(INSTALL_KEY_HEADER, installKey)
            .delete()
            .build()
        execute(request, mapError = ::feedbackErrorOf) { }
    }

    private fun url(path: String): HttpUrl {
        val base = baseUrl ?: appError(AppError.DataUnavailable)
        return base.newBuilder().addPathSegments(path).build()
    }

    private fun get(url: HttpUrl): Request = Request.Builder().url(url).get().build()

    private suspend fun <T> execute(
        request: Request,
        callTimeoutSeconds: Long = CALL_TIMEOUT_SECONDS,
        mapError: (Response) -> AppError = ::errorOf,
        read: (Response) -> T,
    ): T {
        val call = httpClient.newCall(request).apply { timeout().timeout(callTimeoutSeconds, TimeUnit.SECONDS) }
        val response = call.await()
        return response.use {
            withContext(ioDispatcher) {
                ensureActive()
                if (!response.isSuccessful) throw AppException(mapError(response))
                read(response)
            }
        }
    }

    private fun errorBody(response: Response): BackendErrorDto? = runCatching {
        response.body.readBytesWithin(MAX_ERROR_BYTES).decodeToString()
    }
        .mapCatching { json.decodeFromString(BackendErrorDto.serializer(), it) }
        .getOrNull()

    private fun proposalErrorOf(response: Response): AppError {
        val error = errorBody(response)
        val code = error?.error
        val refusal = when {
            code == null -> null
            code.startsWith("INVALID_") || code == "LICENCE_REQUIRED" -> ProposalRefusal.INVALID
            else -> ProposalRefusal.entries.firstOrNull { it.name == code }
        }
        return when {
            refusal != null -> AppError.ProposalRefused(refusal, error?.field)
            code == "NOT_FOUND" || response.code == 404 -> AppError.NotFound
            response.code == 429 -> AppError.ProposalRefused(ProposalRefusal.RATE_LIMITED)
            response.code == 413 -> AppError.ProposalRefused(ProposalRefusal.TOO_LARGE)
            response.code in 400..499 -> AppError.UnexpectedResponse
            else -> AppError.Http(response.code)
        }
    }

    private fun feedbackErrorOf(response: Response): AppError {
        val code = errorBody(response)?.error
        val refusal = when (code) {
            null -> null
            "TOO_MANY_OPEN" -> FeedbackRefusal.TOO_MANY_OPEN
            "BAD_KIND", "BAD_REQUEST" -> FeedbackRefusal.INVALID
            else -> FeedbackRefusal.entries.firstOrNull { it.name == code }
        }
        return when {
            refusal != null -> AppError.FeedbackRefused(refusal)
            code == "NOT_FOUND" || response.code == 404 -> AppError.NotFound
            response.code == 429 -> AppError.FeedbackRefused(FeedbackRefusal.RATE_LIMITED)
            response.code == 413 -> AppError.FeedbackRefused(FeedbackRefusal.TEXT_TOO_LONG)
            response.code in 400..499 -> AppError.UnexpectedResponse
            else -> AppError.Http(response.code)
        }
    }

    private fun errorOf(response: Response): AppError {
        val code = errorBody(response)?.error
        TrackRejection.entries.firstOrNull { it.name == code }?.let { return AppError.TrackRejected(it) }
        return when {
            code == "NOT_FOUND" || code == "UNKNOWN_CANYON" || response.code == 404 -> AppError.NotFound
            response.code == 429 -> AppError.TrackRejected(TrackRejection.RATE_LIMITED)
            response.code == 413 -> AppError.TrackRejected(TrackRejection.TOO_LARGE)
            response.code in 400..499 -> AppError.UnexpectedResponse
            else -> AppError.Http(response.code)
        }
    }

    private fun <T> decode(response: Response, deserializer: DeserializationStrategy<T>): T {
        val text = response.body.readBytesWithin(MAX_JSON_BYTES).decodeToString()
        return try {
            json.decodeFromString(deserializer, text)
        } catch (exception: SerializationException) {
            throw AppException(AppError.UnexpectedResponse, exception)
        } catch (exception: IllegalArgumentException) {
            throw AppException(AppError.UnexpectedResponse, exception)
        }
    }

    private companion object {
        const val INSTALL_KEY_HEADER = "X-Install-Key"
        val GPX_MEDIA_TYPE = "application/gpx+xml".toMediaType()
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_MANIFEST_BYTES = 256L * 1024
        const val MAX_JSON_BYTES = 8L * 1024 * 1024
        const val MAX_ERROR_BYTES = 4L * 1024
        const val CALL_TIMEOUT_SECONDS = 60L
        const val DOWNLOAD_TIMEOUT_SECONDS = 15L * 60
        const val BUFFER_BYTES = 64 * 1024
    }
}
