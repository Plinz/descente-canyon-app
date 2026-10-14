package fr.descentecanyon.app.data.network

import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.AppException
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.ResponseBody

/** Executes the call asynchronously; cancelling the coroutine cancels the HTTP call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        },
    )
}

/** Fails with a typed error when the response status is not 2xx. */
internal fun Response.requireSuccess(): Response {
    if (isSuccessful) return this
    close()
    throw AppException(if (code == 404) AppError.NotFound else AppError.Http(code))
}

/**
 * Reads the whole body, refusing bodies larger than [maxBytes] instead of silently truncating
 * them (Jsoup's own HTTP client cut pages at 2 MiB).
 */
internal fun ResponseBody.readBytesWithin(maxBytes: Long): ByteArray {
    val source = source()
    if (source.request(maxBytes + 1)) {
        throw AppException(AppError.UnexpectedResponse, IOException("Response body exceeds $maxBytes bytes"))
    }
    return source.buffer.readByteArray()
}
