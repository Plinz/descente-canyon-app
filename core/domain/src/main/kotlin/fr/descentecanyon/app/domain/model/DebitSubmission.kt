package fr.descentecanyon.app.domain.model

import fr.descentecanyon.app.domain.error.AppError
import java.time.LocalDate

data class DebitSubmission(
    val canyonId: Int,
    val observerName: String,
    val observerEmail: String? = null,
    val observationDate: LocalDate,
    val observationType: ObservationType,
    val debitLevel: NiveauDebit,
    val waterTemperature: WaterTemperature,
    val airTemperature: AirTemperature,
    val comment: String = "",
    val personalComment: String = "",
)

enum class ObservationType {
    NON_PARCOURU,
    PARCOURU,
}

enum class WaterTemperature {
    CHAUDE,
    DOUCE,
    FROIDE,
    TRES_FROIDE,
    GLACEE,
    INCONNUE,
}

enum class AirTemperature {
    SUPER_CHAUD,
    CHAUD,
    BON,
    FRISQUET,
    FROID,
    INCONNUE,
}

enum class DebitSubmissionStatus {
    /** The site published the report (redirect to the observations page, or found when read back). */
    SUBMITTED,

    /**
     * The site left the form without error but the report is not visible yet (confirmation page,
     * moderation...): the user is invited to check on the site.
     */
    SUBMITTED_UNCONFIRMED,

    /** No connection: the report is stored and sent automatically once online. */
    QUEUED_OFFLINE,
}

/** A debit report stored on the device, waiting to be sent or, once refused by the site, kept for the user. */
data class PendingDebitSubmission(
    val id: Long,
    val submission: DebitSubmission,
    val createdAtEpochMs: Long,
    /** Set when the site refused the report: it is no longer sent until the user retries it. */
    val rejection: PendingDebitRejection? = null,
) {
    /**
     * Reports written while logged in carry no e-mail: the site only accepts them with a session.
     */
    val needsLogin: Boolean get() = submission.observerEmail.isNullOrBlank()
}

/** The site refused a stored report; [siteMessage] is the error it displayed, if any. */
data class PendingDebitRejection(val atEpochMs: Long, val siteMessage: String?)

/** Outcome of sending the reports stored while offline. */
data class PendingDebitSyncResult(
    val sent: List<SentDebitSubmission> = emptyList(),
    /** Reports the site refused; they stay stored, out of the queue, and the user is told. */
    val rejected: List<RejectedDebitSubmission> = emptyList(),
    /** Why the remaining reports are still queued (connectivity, login needed...), or null when the queue is empty. */
    val stoppedBy: AppError? = null,
) {
    val sentCount: Int get() = sent.size
}

/** A stored report the site accepted; [confirmed] is false when it could not be read back yet. */
data class SentDebitSubmission(val canyonId: Int, val observationDate: LocalDate, val confirmed: Boolean)

data class RejectedDebitSubmission(val canyonId: Int, val observationDate: LocalDate, val error: AppError)

/** How the site acknowledged a debit report. */
enum class DebitDelivery {
    CONFIRMED,
    UNCONFIRMED,
}
