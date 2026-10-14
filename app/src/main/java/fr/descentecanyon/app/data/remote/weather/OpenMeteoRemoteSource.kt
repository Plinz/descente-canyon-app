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
        private const val DEBIT_DAILY_VARIABLES = "precipitation_sum,temperature_2m_mean"
        private const val DEBIT_ARCHIVE_MODEL = "era5"
        private const val DEBIT_FORECAST_MODEL = "ecmwf_ifs025"
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
            parameter("timezone", "auto")
        }.body()
    }

    /**
     * Daily ECMWF IFS 0.25 deg values for the last [pastDays] days and the next [forecastDays] days.
     * Same grid as ERA5, which keeps the debit model inputs consistent with its training data.
     */
    open suspend fun fetchDebitForecast(
        latitude: Double,
        longitude: Double,
        pastDays: Int,
        forecastDays: Int,
    ): OpenMeteoDailyResponseDto {
        return httpClient.get("/v1/forecast") {
            parameter("latitude", latitude)
            parameter("longitude", longitude)
            parameter("daily", DEBIT_DAILY_VARIABLES)
            parameter("past_days", pastDays)
            parameter("forecast_days", forecastDays)
            parameter("models", DEBIT_FORECAST_MODEL)
            parameter("timezone", "auto")
        }.body()
    }

    /** Daily ERA5 reanalysis (the debit model training source). The last ~5 days are null. */
    open suspend fun fetchDebitArchive(
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
            parameter("daily", DEBIT_DAILY_VARIABLES)
            parameter("models", DEBIT_ARCHIVE_MODEL)
            parameter("timezone", "auto")
        }.body()
    }
}
