package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.DebitPredictionSupport

interface DebitPredictionSupportRepository {
    suspend fun getPredictionSupport(detail: CanyonDetail): Result<DebitPredictionSupport>

    /** True when the weather window of the canyon is saved: its next estimate downloads only the last days. */
    suspend fun hasWeatherHistory(canyonId: Int): Boolean = true
}
