package fr.descentecanyon.app.domain.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackTest {

    private val at = Instant.parse("2026-11-28T09:00:00Z")

    private fun message(id: String, status: FeedbackStatus = FeedbackStatus.NEW, reply: String? = null, queued: Boolean = false) =
        FeedbackMessage(
            id = id,
            kind = FeedbackKind.BUG,
            text = "Texte du message",
            status = status,
            createdAt = at,
            reply = reply,
            isQueued = queued,
        )

    @Test
    fun `a new reply and a decision are told, a message read is not`() {
        val before = listOf(message("a"), message("b"), message("c"), message("d", FeedbackStatus.PLANNED, reply = "Prévu"))
        val after = listOf(
            message("a", FeedbackStatus.READ),
            message("b", FeedbackStatus.READ, reply = "Merci, on regarde."),
            message("c", FeedbackStatus.DECLINED),
            message("d", FeedbackStatus.PLANNED, reply = "Prévu"),
        )

        assertEquals(listOf("b", "c"), answeredSince(before, after).map { it.id })
    }

    @Test
    fun `a changed reply is told again`() {
        val before = listOf(message("a", FeedbackStatus.PLANNED, reply = "Prévu"))
        val after = listOf(message("a", FeedbackStatus.DONE, reply = "C'est fait dans la 3.3."))

        assertEquals(listOf("a"), answeredSince(before, after).map { it.id })
    }

    @Test
    fun `a message the phone did not know is told only for its reply, and a queued one never`() {
        val after =
            listOf(message("a", FeedbackStatus.DONE), message("b", FeedbackStatus.READ, reply = "Merci"), message("q", queued = true))

        assertEquals(listOf("b"), answeredSince(emptyList(), after).map { it.id })
    }

    @Test
    fun `the length counted is the server's, blanks around the text left out`() {
        assertFalse(FeedbackLimits.isSendable("   court   "))
        assertTrue(FeedbackLimits.isSendable("  assez long pour partir  "))
        assertFalse(FeedbackLimits.isSendable("x".repeat(FeedbackLimits.TEXT_MAX + 1)))
        assertTrue(FeedbackStatus.PLANNED.isOpen)
        assertFalse(FeedbackStatus.DONE.isOpen)
    }
}
