package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.repository.WeatherRepository
import javax.inject.Inject

class GetCanyonWeatherUseCase @Inject constructor(private val weatherRepository: WeatherRepository) {
    suspend operator fun invoke(detail: CanyonDetail): Result<CanyonWeather> = weatherRepository.getCanyonWeather(detail)

    /** Last forecast kept on the device, shown when the network is unavailable. */
    suspend fun cached(canyonId: Int): CanyonWeather? = weatherRepository.getCachedCanyonWeather(canyonId)
}
