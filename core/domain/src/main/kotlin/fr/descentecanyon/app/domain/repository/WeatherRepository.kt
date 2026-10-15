package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonWeather

interface WeatherRepository {
    /** Downloads the forecast and keeps it for offline use. */
    suspend fun getCanyonWeather(detail: CanyonDetail): Result<CanyonWeather>

    /** Last forecast downloaded for the canyon if it is recent enough, recomputed for the current time. */
    suspend fun getCachedCanyonWeather(canyonId: Int): CanyonWeather?
}
