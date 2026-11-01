package fr.descentecanyon.app.outing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InexactTriggerTest {

    private val minute = 60_000L
    private val now = 1_000_000_000L

    /** Android rings an inexact alarm within three quarters of the wait after it, an hour at most. */
    private fun latestRing(askedAt: Long) = askedAt + minOf((askedAt - now) * 3 / 4, 60 * minute)

    @Test
    fun `a reminder hours ahead is asked an hour early, so it rings before its time`() {
        val target = now + 7 * 60 * minute
        val asked = inexactTriggerAt(target, now)
        assertEquals(target - 60 * minute, asked)
        assertTrue(latestRing(asked) <= target)
    }

    @Test
    fun `a reminder soon is asked early by what Android may add`() {
        listOf(5L, 20L, 40L, 80L).forEach { minutes ->
            val target = now + minutes * minute
            val asked = inexactTriggerAt(target, now)
            assertTrue("$minutes min", asked >= now && latestRing(asked) <= target)
        }
        // Already past: at once.
        assertEquals(now - minute, inexactTriggerAt(now - minute, now))
    }
}
