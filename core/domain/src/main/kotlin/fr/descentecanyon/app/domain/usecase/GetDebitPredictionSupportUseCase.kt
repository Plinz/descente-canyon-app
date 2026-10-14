package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.DebitPredictionSupport
import fr.descentecanyon.app.domain.repository.DebitPredictionSupportRepository
import javax.inject.Inject

class GetDebitPredictionSupportUseCase @Inject constructor(private val repository: DebitPredictionSupportRepository) {
    suspend operator fun invoke(detail: CanyonDetail): Result<DebitPredictionSupport> = repository.getPredictionSupport(detail)
}
