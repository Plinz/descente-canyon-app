package fr.descentecanyon.app.data.remote.weather

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenMeteoDailyResponseDto(
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val daily: DailyForecastDto? = null,
)

@Serializable
data class DailyForecastDto(
    val time: List<String> = emptyList(),
    @SerialName("precipitation_sum")
    val precipitationSum: List<Double?> = emptyList(),
    @SerialName("rain_sum")
    val rainSum: List<Double?> = emptyList(),
    @SerialName("snowfall_sum")
    val snowfallSum: List<Double?> = emptyList(),
    @SerialName("temperature_2m_mean")
    val temperature2mMean: List<Double?> = emptyList(),
    @SerialName("temperature_2m_min")
    val temperature2mMin: List<Double?> = emptyList(),
    @SerialName("temperature_2m_max")
    val temperature2mMax: List<Double?> = emptyList(),
    @SerialName("precipitation_hours")
    val precipitationHours: List<Double?> = emptyList(),
    @SerialName("weather_code")
    val weatherCode: List<Int?> = emptyList(),
)
