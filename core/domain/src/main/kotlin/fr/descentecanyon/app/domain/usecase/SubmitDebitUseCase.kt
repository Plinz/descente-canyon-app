package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.DebitSubmissionStatus
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.LogbookSource
import fr.descentecanyon.app.domain.model.ObservationType
import fr.descentecanyon.app.domain.repository.CanyonRepository
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import fr.descentecanyon.app.domain.repository.LogbookRepository
import javax.inject.Inject

/**
 * Sends a flow report (or queues it offline). A report of a descent also logs the outing, with the
 * flow seen and the personal comment, unless that outing is already in the logbook.
 */
class SubmitDebitUseCase @Inject constructor(
    private val repository: DebitSubmissionRepository,
    private val logbookRepository: LogbookRepository,
    private val canyonRepository: CanyonRepository,
) {
    suspend operator fun invoke(submission: DebitSubmission): Result<DebitSubmissionStatus> =
        repository.submit(submission).onSuccess { if (submission.observationType == ObservationType.PARCOURU) logDescent(submission) }

    private suspend fun logDescent(submission: DebitSubmission) {
        // The report is sent: a logbook failure must not turn it into an error.
        suspendRunCatching<Unit> {
            val name = canyonRepository.getCanyonSummaries(setOf(submission.canyonId)).firstOrNull()?.nom ?: return@suspendRunCatching
            logbookRepository.record(
                LogbookEntry(
                    canyonId = submission.canyonId,
                    canyonName = name,
                    date = submission.observationDate,
                    note = submission.personalComment.trim().ifEmpty { null },
                    debitLevel = submission.debitLevel,
                    source = LogbookSource.REPORT,
                ),
            )
        }
    }
}
