package fr.descentecanyon.app.domain.model

import java.time.Instant

/**
 * The idea box: a bug, an idea or a change to an existing feature, written in the app and sent to
 * the app's server, where the operator reads it and may answer. The answer and what became of the
 * message come back to the app.
 */
enum class FeedbackKind { BUG, IDEA, CHANGE }

enum class FeedbackStatus {
    /** Not read yet. */
    NEW,
    READ,
    PLANNED,
    DONE,
    DECLINED,
    ;

    val isOpen: Boolean get() = this == NEW || this == READ || this == PLANNED
}

/** One message of this installation, as the server keeps it, or still waiting for the network ([isQueued]). */
data class FeedbackMessage(
    /** The server's id, or a local one while [isQueued]. */
    val id: String,
    val kind: FeedbackKind,
    val text: String,
    val status: FeedbackStatus,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
    val reply: String? = null,
    val repliedAt: Instant? = null,
    /** Written without network: it leaves as soon as the phone is online again. */
    val isQueued: Boolean = false,
    /** The reply came after the person last looked at the messages. */
    val isReplyNew: Boolean = false,
)

/** What the person leaves attached by default so that a bug can be reproduced. */
data class FeedbackTechnicalInfo(
    val appVersion: String,
    val versionCode: Long,
    /** "14 (API 34)". */
    val android: String,
    /** "Google Pixel 5". */
    val device: String,
    /** The language of the app, "fr-FR". */
    val language: String,
)

/** Who to answer by e-mail too: the descente-canyon.com account the app is logged in with. */
data class FeedbackContact(val username: String, val email: String?)

data class FeedbackDraft(
    val kind: FeedbackKind,
    val text: String,
    val technical: FeedbackTechnicalInfo? = null,
    val contact: FeedbackContact? = null,
)

/** A message sent at once, or kept on the phone until the network comes back. */
enum class FeedbackSendResult { SENT, QUEUED }

/** Why the server refused a message. */
enum class FeedbackRefusal { TEXT_TOO_SHORT, TEXT_TOO_LONG, BAD_EMAIL, RATE_LIMITED, TOO_MANY_OPEN, BLOCKED, INVALID }

object FeedbackLimits {
    const val TEXT_MIN = 10
    const val TEXT_MAX = 4000

    /** The length the server counts: blanks around the text are not. */
    fun length(text: String): Int = text.trim().length

    fun isSendable(text: String): Boolean = length(text) in TEXT_MIN..TEXT_MAX
}

/**
 * The messages of [after] the person should hear about since [before]: a new or changed reply, or a
 * decision (planned, done, declined). A message missing from [before] is told only for its reply.
 */
fun answeredSince(before: List<FeedbackMessage>, after: List<FeedbackMessage>): List<FeedbackMessage> {
    val previous = before.filterNot { it.isQueued }.associateBy { it.id }
    return after.filter { message ->
        if (message.isQueued) return@filter false
        val old = previous[message.id]
        val newReply = message.reply != null && message.reply != old?.reply
        val decided = old != null && message.status != old.status && message.status in DECISIONS
        newReply || decided
    }
}

private val DECISIONS = setOf(FeedbackStatus.PLANNED, FeedbackStatus.DONE, FeedbackStatus.DECLINED)
