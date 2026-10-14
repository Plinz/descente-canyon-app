package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonWeather

interface WeatherRepository {
    suspend fun getCanyonWeather(detail: CanyonDetail): Result<CanyonWeather>
}
