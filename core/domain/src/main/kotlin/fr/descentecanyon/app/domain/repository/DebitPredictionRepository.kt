package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.DebitPredictionInfoSummary

interface DebitPredictionRepository {
    /** Computes the estimate (needs the network for the weather) and keeps it for offline use. */
    suspend fun getPredictions(detail: CanyonDetail): Result<CanyonDebitPredictions>

    /** Last estimate computed for the canyon if it still covers today. */
    suspend fun getCachedPredictions(canyonId: Int): CanyonDebitPredictions?

    /** Facts about the embedded model (training data, main drivers) for the explanation screen. */
    suspend fun getModelSummary(): Result<DebitPredictionInfoSummary>
}
