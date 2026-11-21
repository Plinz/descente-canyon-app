package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.DebitPredictionInfoSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface DebitPredictionRepository {
    /** Computes the estimate (needs the network for the weather) and keeps it for offline use. */
    suspend fun getPredictions(detail: CanyonDetail): Result<CanyonDebitPredictions>

    /** Last estimate computed for the canyon if it still covers today. */
    suspend fun getCachedPredictions(canyonId: Int): CanyonDebitPredictions?

    /** Facts about the embedded model (training data, main drivers) for the explanation screen. */
    suspend fun getModelSummary(): Result<DebitPredictionInfoSummary>

    /**
     * Estimates of several canyons for a list: those saved on the device at once, then the ones
     * older than a few hours computed again (online), one by one in the order of [canyonIds], each
     * emission holding every estimate known so far. The downloads are spaced out to stay within
     * the free quota of the weather service.
     */
    fun observeEstimates(canyonIds: List<Int>): Flow<Map<Int, CanyonDebitPredictions>> = flowOf(emptyMap())
}
