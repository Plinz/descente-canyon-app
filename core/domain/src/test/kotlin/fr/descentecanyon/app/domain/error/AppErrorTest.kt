package fr.descentecanyon.app.domain.error

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppErrorTest {

    @Test
    fun `transport exceptions are classified through their cause chain`() {
        assertEquals(AppError.Offline, UnknownHostException("www.descente-canyon.com").toAppError())
        assertEquals(AppError.Timeout, IOException("wrapper", SocketTimeoutException("read")).toAppError())
        assertEquals(AppError.NetworkFailure, IOException("reset").toAppError())
        assertEquals(AppError.SessionExpired, IllegalStateException(AppException(AppError.SessionExpired)).toAppError())
        assertTrue(IllegalArgumentException("bug").toAppError() is AppError.Unknown)
    }

    @Test
    fun `suspendRunCatching normalizes failures`() {
        val result = suspendRunCatching { throw UnknownHostException("x") }

        assertEquals(AppError.Offline, (result.exceptionOrNull() as AppException).error)
    }

    @Test
    fun `suspendRunCatching never swallows cancellation`() {
        try {
            suspendRunCatching { throw CancellationException("cancelled") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `transient errors are retried and site refusals are not`() {
        assertTrue(AppError.Offline.isTransient())
        assertTrue(AppError.Http(503).isTransient())
        assertTrue(AppError.UnexpectedResponse.isTransient())
        assertFalse(AppError.Http(400).isTransient())
        assertFalse(AppError.Rejected("Date invalide").isTransient())
        assertFalse(AppError.SessionExpired.isTransient())
        assertTrue(AppError.SessionExpired.requiresLogin())
    }
}
