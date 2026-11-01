package fr.descentecanyon.app.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteReportsRefreshPolicyTest {

    private val hour = 60 * 60 * 1000L
    private val now = 100 * hour

    @Test
    fun `a list never read is read`() {
        assertTrue(SiteReportsRefreshPolicy.isDue(readAt = null, lastSentAt = null, now = now))
    }

    @Test
    fun `a list read an hour ago is not read again at each reconnection`() {
        assertFalse(SiteReportsRefreshPolicy.isDue(readAt = now - hour, lastSentAt = null, now = now))
        assertFalse(SiteReportsRefreshPolicy.isDue(readAt = now - hour, lastSentAt = now - 2 * hour, now = now))
    }

    @Test
    fun `a report sent after the last reading makes it due`() {
        assertTrue(SiteReportsRefreshPolicy.isDue(readAt = now - hour, lastSentAt = now - hour / 2, now = now))
    }

    @Test
    fun `a list older than the maximum age is read again`() {
        assertTrue(SiteReportsRefreshPolicy.isDue(readAt = now - SiteReportsRefreshPolicy.MAX_AGE_MS, lastSentAt = null, now = now))
    }
}
