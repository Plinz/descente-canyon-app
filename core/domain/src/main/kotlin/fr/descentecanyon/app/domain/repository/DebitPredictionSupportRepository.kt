package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.DebitPredictionSupport

interface DebitPredictionSupportRepository {
    suspend fun getPredictionSupport(detail: CanyonDetail): Result<DebitPredictionSupport>
}
