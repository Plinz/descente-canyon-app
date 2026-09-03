package fr.descentecanyon.app.data.remote.weather

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class OpenMeteoRemoteSource @Inject constructor(
    private val httpClient: HttpClient,
) {

    companion object {
        private const val DAILY_VARIABLES =
            "precipitation_sum,rain_sum,snowfall_sum,temperature_2m_mean,temperature_2m_min,temperature_2m_max,precipitation_hours,weather_code"
    }

    open suspend fun fetchForecast(
        latitude: Double,
        longitude: Double,
    ): OpenMeteoForecastDto {
        return httpClient.get("/v1/forecast") {
            parameter("latitude", latitude)
            parameter("longitude", longitude)
            parameter(
                "hourly",
                "precipitation,rain,showers,precipitation_probability,weather_code",
            )
            parameter("past_hours", 72)
            parameter("forecast_hours", 48)
            parameter("daily", DAILY_VARIABLES)
            parameter("forecast_days", 5)
            parameter("timezone", "auto")
        }.body()
    }

    open suspend fun fetchDailyForecast(
        latitude: Double,
        longitude: Double,
        forecastDays: Int = 3,
    ): OpenMeteoDailyResponseDto {
        return httpClient.get("/v1/forecast") {
            parameter("latitude", latitude)
            parameter("longitude", longitude)
            parameter("daily", DAILY_VARIABLES)
            parameter("forecast_days", forecastDays)
            parameter("timezone", "auto")
        }.body()
    }

    open suspend fun fetchDailyArchive(
        latitude: Double,
        longitude: Double,
        startDate: String,
        endDate: String,
    ): OpenMeteoDailyResponseDto {
        return httpClient.get("https://archive-api.open-meteo.com/v1/archive") {
            parameter("latitude", latitude)
            parameter("longitude", longitude)
            parameter("start_date", startDate)
            parameter("end_date", endDate)
            parameter("daily", DAILY_VARIABLES)
            parameter("timezone", "auto")
        }.body()
    }
}
