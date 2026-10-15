package fr.descentecanyon.app.domain.error

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Typed failure of an app operation. The data layer only fails with [AppException]s carrying one of
 * these values; the UI maps them to localized messages and never shows raw exception messages.
 */
sealed interface AppError {

    /** No usable network connection (checked before the request, or DNS resolution failed). */
    data object Offline : AppError

    /** The remote service did not answer in time. */
    data object Timeout : AppError

    /** Any other transport failure (connection reset, TLS error...). */
    data object NetworkFailure : AppError

    /** The remote page or resource does not exist (HTTP 404). */
    data object NotFound : AppError

    /** The remote service answered with an unexpected HTTP status. */
    data class Http(val statusCode: Int) : AppError

    /** A page of descente-canyon.com no longer has the expected structure. */
    data object SiteFormatChanged : AppError

    /**
     * The remote service answered something the app cannot interpret: an error or maintenance page
     * instead of the expected one, an oversized or malformed body, a submission neither confirmed
     * nor rejected...
     */
    data object UnexpectedResponse : AppError

    /** The site refused a submission; [serverMessage] is the error text it displayed, if any. */
    data class Rejected(val serverMessage: String?) : AppError

    /** The action needs a descente-canyon.com account and no session is open. */
    data object AuthenticationRequired : AppError

    /** A session was open but the site now treats the user as anonymous. */
    data object SessionExpired : AppError

    /** The site rejected the username/password pair. */
    data object InvalidCredentials : AppError

    /** The data needed for the operation does not exist (no coordinates, nothing cached offline...). */
    data object DataUnavailable : AppError

    /** Writing to local storage (database, gallery, cache files) failed. */
    data object StorageFailure : AppError

    /** Unclassified failure; [cause] is kept for logs only. */
    data class Unknown(val cause: Throwable? = null) : AppError {
        override fun toString(): String = "Unknown(${cause?.let { it::class.simpleName + ": " + it.message }})"
    }
}

/** Failures that may succeed later without any user action (connectivity, server hiccups). */
fun AppError.isTransient(): Boolean = when (this) {
    AppError.Offline, AppError.Timeout, AppError.NetworkFailure, AppError.UnexpectedResponse,
    AppError.SiteFormatChanged, AppError.StorageFailure, is AppError.Unknown,
    -> true

    is AppError.Http -> statusCode >= 500 || statusCode == 408 || statusCode == 429

    else -> false
}

/** Failures that need the user to log in (again). */
fun AppError.requiresLogin(): Boolean = this == AppError.AuthenticationRequired || this == AppError.SessionExpired

/** Failures caused by the device connectivity rather than by the remote service. */
fun AppError.isConnectivityIssue(): Boolean = this == AppError.Offline || this == AppError.Timeout || this == AppError.NetworkFailure

/** Exception carrying an [AppError] through `Result` and coroutine boundaries. */
class AppException(val error: AppError, cause: Throwable? = null) : Exception(error.toString(), cause)

/** Classifies any throwable as an [AppError]. Transport exceptions are recognized through their cause chain. */
fun Throwable.toAppError(): AppError {
    if (this is AppException) return error
    for (throwable in generateSequence(this) { it.cause }) {
        when (throwable) {
            is AppException -> return throwable.error

            is UnknownHostException, is UnresolvedAddressException, is NoRouteToHostException -> return AppError.Offline

            is SocketTimeoutException -> return AppError.Timeout

            is InterruptedIOException -> if (throwable.message?.contains("timeout", ignoreCase = true) == true) {
                return AppError.Timeout
            }

            is ConnectException -> return AppError.NetworkFailure

            else -> Unit
        }
    }
    return if (this is IOException) AppError.NetworkFailure else AppError.Unknown(this)
}

/** Wraps any throwable in an [AppException], keeping existing ones untouched. */
fun Throwable.asAppException(): AppException = this as? AppException ?: AppException(toAppError(), this)

/** Shorthand to fail with a typed error. */
fun appError(error: AppError, cause: Throwable? = null): Nothing = throw AppException(error, cause)

/** [Result.failure] carrying a typed error. */
fun <T> failure(error: AppError): Result<T> = Result.failure(AppException(error))

/** The typed error of a failed [Result], or `null` when it succeeded. */
fun Result<*>.appErrorOrNull(): AppError? = exceptionOrNull()?.toAppError()

/**
 * Like [runCatching] but safe for coroutines: [CancellationException] is rethrown instead of being
 * turned into a failure, and every other throwable is normalized to an [AppException].
 */
inline fun <T> suspendRunCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    Result.failure(throwable.asAppException())
}
