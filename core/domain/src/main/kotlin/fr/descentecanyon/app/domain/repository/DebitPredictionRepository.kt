package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CanyonDebitPredictions
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.DebitPredictionInfoSummary

interface DebitPredictionRepository {
    suspend fun getPredictions(detail: CanyonDetail): Result<CanyonDebitPredictions>

    /** Facts about the embedded model (training data, main drivers) for the explanation screen. */
    suspend fun getModelSummary(): Result<DebitPredictionInfoSummary>
}
