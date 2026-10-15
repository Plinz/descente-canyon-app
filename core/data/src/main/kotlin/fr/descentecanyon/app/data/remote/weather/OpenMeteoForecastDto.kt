package fr.descentecanyon.app.data.remote.weather

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenMeteoForecastDto(
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val hourly: HourlyForecastDto? = null,
    val daily: DailyForecastDto? = null,
)

@Serializable
data class HourlyForecastDto(
    val time: List<String> = emptyList(),
    val precipitation: List<Double?> = emptyList(),
    val rain: List<Double?> = emptyList(),
    val showers: List<Double?> = emptyList(),
    @SerialName("precipitation_probability")
    val precipitationProbability: List<Int?> = emptyList(),
    @SerialName("weather_code")
    val weatherCode: List<Int?> = emptyList(),
)
