package fr.descentecanyon.app.data.remote.scraper

import fr.descentecanyon.app.domain.model.DebitDelivery
import fr.descentecanyon.app.domain.model.DebitSubmission

interface DebitSubmissionRemoteSource {
    /** Posts the report on the site; fails with a typed error when it is not accepted. */
    suspend fun submit(submission: DebitSubmission): Result<DebitDelivery>
}
