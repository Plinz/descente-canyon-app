package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.DebitEstimationProgress
import fr.descentecanyon.app.domain.model.SearchCriteria
import fr.descentecanyon.app.domain.repository.DebitEstimateRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class EstimateCanyonsDebitUseCase @Inject constructor(private val repository: DebitEstimateRepository) {
    /** Nothing is requested for more than [maxCanyons] canyons: the area is too wide. */
    operator fun invoke(canyonIds: List<Int>, maxCanyons: Int = SearchCriteria.MAX_DEBIT_ESTIMATE_CANYONS): Flow<DebitEstimationProgress> =
        if (canyonIds.isEmpty() || canyonIds.size > maxCanyons) emptyFlow() else repository.estimate(canyonIds.distinct())
}
