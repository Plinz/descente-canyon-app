package fr.descentecanyon.app.domain.usecase

import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.Place
import fr.descentecanyon.app.domain.repository.WeatherRepository
import javax.inject.Inject

/** The forecast at the place of a stay: the canyons around share its weather, so it is shown once per day. */
class GetPlaceWeatherUseCase @Inject constructor(private val weatherRepository: WeatherRepository) {
    suspend operator fun invoke(place: Place): Result<CanyonWeather> = weatherRepository.getPlaceWeather(place)

    /** Last forecast kept on the device, shown when the network is unavailable. */
    suspend fun cached(place: Place): CanyonWeather? = weatherRepository.getCachedPlaceWeather(place)
}
