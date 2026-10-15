package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.repository.DebitPredictionRepository
import javax.inject.Inject

class GetCanyonDebitPredictionsUseCase @Inject constructor(private val repository: DebitPredictionRepository) {
    suspend operator fun invoke(detail: CanyonDetail): Result<CanyonDebitPredictions> = repository.getPredictions(detail)

    /** Last estimate kept on the device, shown when the network is unavailable. */
    suspend fun cached(canyonId: Int): CanyonDebitPredictions? = repository.getCachedPredictions(canyonId)
}
