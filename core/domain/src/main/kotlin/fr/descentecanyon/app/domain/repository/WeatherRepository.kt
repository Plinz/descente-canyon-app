package fr.descentecanyon.app.domain.repository

import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.Place

interface WeatherRepository {
    /** Downloads the forecast and keeps it for offline use. */
    suspend fun getCanyonWeather(detail: CanyonDetail): Result<CanyonWeather>

    /** Last forecast downloaded for the canyon if it is recent enough, recomputed for the current time. */
    suspend fun getCachedCanyonWeather(canyonId: Int): CanyonWeather?

    /** Downloads the forecast at a starting place (the campsite of a stay) and keeps it for offline use. */
    suspend fun getPlaceWeather(place: Place): Result<CanyonWeather>

    /** Last forecast downloaded at the place if it is recent enough and the place has not moved. */
    suspend fun getCachedPlaceWeather(place: Place): CanyonWeather?
}
