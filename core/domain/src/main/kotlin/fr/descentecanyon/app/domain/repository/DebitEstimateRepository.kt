package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.DebitEstimationProgress
import kotlinx.coroutines.flow.Flow

interface DebitEstimateRepository {
    /**
     * Estimates the flow of each canyon, a few at a time, emitting the progress after each one.
     * The estimate kept on the device is used when it still covers today, the weather is fetched
     * otherwise (not at all when offline). Cancelling the collection stops the work.
     */
    fun estimate(canyonIds: List<Int>): Flow<DebitEstimationProgress>
}
