package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.model.DebitEstimationProgress
import fr.descentecanyon.app.domain.repository.DebitEstimateRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** No weather in the end-to-end tests: no canyon can be estimated. */
@Singleton
class FakeDebitEstimateRepository @Inject constructor() : DebitEstimateRepository {
    override fun estimate(canyonIds: List<Int>): Flow<DebitEstimationProgress> = flowOf(
        DebitEstimationProgress(
            total = canyonIds.size,
            done = canyonIds.size,
            estimates = emptyMap(),
            unavailableIds = canyonIds.toSet(),
        ),
    )
}
