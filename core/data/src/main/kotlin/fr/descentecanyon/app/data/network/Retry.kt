package fr.descentecanyon.app.data.network

import fr.descentecanyon.app.domain.error.isConnectivityIssue
import fr.descentecanyon.app.domain.error.toAppError
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay

/**
 * Runs [block] up to [attempts] times while it fails on a connectivity issue (offline, timeout,
 * connection failure); any other failure, and cancellation, propagates immediately.
 */
internal suspend fun <T> retryOnConnectivityFailure(attempts: Int = 2, delayMs: Long = 600L, block: suspend () -> T): T {
    repeat(attempts - 1) {
        try {
            return block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            if (!throwable.toAppError().isConnectivityIssue()) throw throwable
        }
        delay(delayMs)
    }
    return block()
}
