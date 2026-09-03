package fr.descentecanyon.app.ui.canyon

import androidx.annotation.StringRes
import fr.descentecanyon.app.R

object WeatherIconHelper {
    fun getEmojiForWeatherCode(weatherCode: Int?): String {
        return when (weatherCode) {
            0 -> "☀️" // Clear sky
            1, 2, 3 -> "⛅" // Mainly clear, partly cloudy, and overcast
            45, 48 -> "🌫️" // Fog
            51, 53, 55, 56, 57 -> "🌦️" // Drizzle
            61, 63, 65, 66, 67 -> "🌧️" // Rain
            71, 73, 75, 77 -> "❄️" // Snow
            80, 81, 82 -> "🌧️" // Showers
            85, 86 -> "🌨️" // Snow showers
            95, 96, 99 -> "⛈️" // Thunderstorm
            else -> "❓"
        }
    }

    @StringRes
    fun getDescriptionForWeatherCode(weatherCode: Int?): Int {
        return when (weatherCode) {
            0 -> R.string.weather_code_clear
            1, 2, 3 -> R.string.weather_code_cloudy
            45, 48 -> R.string.weather_code_fog
            51, 53, 55, 56, 57 -> R.string.weather_code_drizzle
            61, 63, 65, 66, 67 -> R.string.weather_code_rain
            71, 73, 75, 77 -> R.string.weather_code_snow
            80, 81, 82 -> R.string.weather_code_showers
            85, 86 -> R.string.weather_code_snow_showers
            95, 96, 99 -> R.string.weather_code_thunderstorm
            else -> R.string.weather_code_unknown
        }
    }
}
