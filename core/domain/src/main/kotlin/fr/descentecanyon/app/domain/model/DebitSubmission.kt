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

/** Outcome of sending the reports stored while offline. */
data class PendingDebitSyncResult(
    val sentCount: Int = 0,
    /** Reports the site refused; they are removed from the queue and the user is told. */
    val rejected: List<RejectedDebitSubmission> = emptyList(),
    /** Why the remaining reports are still queued (connectivity, login needed...), or null when the queue is empty. */
    val stoppedBy: AppError? = null,
)

data class RejectedDebitSubmission(val canyonId: Int, val observationDate: LocalDate, val error: AppError)

/** How the site acknowledged a debit report. */
enum class DebitDelivery {
    CONFIRMED,
    UNCONFIRMED,
}
