package fr.descentecanyon.app.data.remote.weather

import fr.descentecanyon.app.data.network.ApiHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Singleton
open class OpenMeteoRemoteSource @Inject constructor(private val httpClient: ApiHttpClient) {

    companion object {
        private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        private const val ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/archive"
        private const val DEBIT_DAILY_VARIABLES = "precipitation_sum,temperature_2m_mean"
        private const val DEBIT_ARCHIVE_MODEL = "era5"
        private const val DEBIT_FORECAST_MODEL = "ecmwf_ifs025"
    }

    open suspend fun fetchForecast(latitude: Double, longitude: Double): OpenMeteoForecastDto {
        val url = FORECAST_URL.withQuery(
            "latitude" to latitude,
            "longitude" to longitude,
            "hourly" to "precipitation,rain,showers,precipitation_probability,weather_code",
            "past_hours" to 72,
            "forecast_hours" to 48,
            "timezone" to "auto",
        )
        return httpClient.getJson(url, OpenMeteoForecastDto.serializer())
    }

    /**
     * Daily ECMWF IFS 0.25 deg values for the last [pastDays] days and the next [forecastDays] days.
     * Same grid as ERA5, which keeps the debit model inputs consistent with its training data.
     */
    open suspend fun fetchDebitForecast(latitude: Double, longitude: Double, pastDays: Int, forecastDays: Int): OpenMeteoDailyResponseDto {
        val url = FORECAST_URL.withQuery(
            "latitude" to latitude,
            "longitude" to longitude,
            "daily" to DEBIT_DAILY_VARIABLES,
            "past_days" to pastDays,
            "forecast_days" to forecastDays,
            "models" to DEBIT_FORECAST_MODEL,
            "timezone" to "auto",
        )
        return httpClient.getJson(url, OpenMeteoDailyResponseDto.serializer())
    }

    /** Daily ERA5 reanalysis (the debit model training source). The last ~5 days are null. */
    open suspend fun fetchDebitArchive(
        latitude: Double,
        longitude: Double,
        startDate: String,
        endDate: String,
    ): OpenMeteoDailyResponseDto {
        val url = ARCHIVE_URL.withQuery(
            "latitude" to latitude,
            "longitude" to longitude,
            "start_date" to startDate,
            "end_date" to endDate,
            "daily" to DEBIT_DAILY_VARIABLES,
            "models" to DEBIT_ARCHIVE_MODEL,
            "timezone" to "auto",
        )
        return httpClient.getJson(url, OpenMeteoDailyResponseDto.serializer())
    }

    private fun String.withQuery(vararg parameters: Pair<String, Any>): HttpUrl = toHttpUrl().newBuilder()
        .apply { parameters.forEach { (name, value) -> addQueryParameter(name, value.toString()) } }
        .build()
}
