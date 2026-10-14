package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.DebitSubmission
import fr.descentecanyon.app.domain.model.DebitSubmissionStatus
import fr.descentecanyon.app.domain.repository.DebitSubmissionRepository
import javax.inject.Inject

class SubmitDebitUseCase @Inject constructor(private val repository: DebitSubmissionRepository) {
    suspend operator fun invoke(submission: DebitSubmission): Result<DebitSubmissionStatus> = repository.submit(submission)
}
