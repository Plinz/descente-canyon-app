package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.network.ApiHttpClient
import fr.descentecanyon.app.data.remote.weather.HourlyForecastDto
import fr.descentecanyon.app.data.remote.weather.OpenMeteoForecastDto
import fr.descentecanyon.app.data.remote.weather.OpenMeteoRemoteSource
import fr.descentecanyon.app.domain.model.Canyon
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonWatershed
import fr.descentecanyon.app.domain.model.GeoBounds
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.domain.model.WeatherLocationSource
import io.mockk.mockk
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherRepositoryImplTest {

    @Test
    fun `uses watershed center when available`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val remoteSource = fakeRemoteSource(forecastDto())
        val repository = WeatherRepositoryImpl(remoteSource, dispatcher)
        val detail = canyonDetail(
            watershed = CanyonWatershed(
                areaKm2 = 12.5,
                bounds = GeoBounds(
                    minLongitude = 6.10,
                    minLatitude = 43.70,
                    maxLongitude = 6.30,
                    maxLatitude = 43.90,
                ),
            ),
        )

        val result = repository.getCanyonWeather(detail)

        assertTrue(result.isSuccess)
        val weather = result.getOrThrow()
        assertEquals(WeatherLocationSource.WATERSHED_CENTER, weather.target.source)
        assertEquals(43.80, weather.target.latitude, 0.001)
        assertEquals(6.20, weather.target.longitude, 0.001)
        assertEquals(10.0, weather.past24HoursPrecipitationMm, 0.001)
        assertEquals(20.0, weather.next24HoursPrecipitationMm, 0.001)
        assertEquals(20.0, weather.next48HoursPrecipitationMm, 0.001)
        assertEquals(7, weather.maxPrecipitationProbabilityNext24Hours)
    }

    @Test
    fun `falls back to entry point when watershed is missing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val remoteSource = fakeRemoteSource(forecastDto())
        val repository = WeatherRepositoryImpl(remoteSource, dispatcher)
        val detail = canyonDetail(
            geoPoints = listOf(
                GeoPoint(id = 1, canyonId = 42, type = GeoPointType.SORTIE, latitude = 43.01, longitude = 6.01),
                GeoPoint(id = 2, canyonId = 42, type = GeoPointType.ENTREE, latitude = 43.02, longitude = 6.02),
            ),
        )

        val result = repository.getCanyonWeather(detail)

        assertTrue(result.isSuccess)
        assertEquals(WeatherLocationSource.ENTRY, result.getOrThrow().target.source)
    }

    private fun forecastDto(): OpenMeteoForecastDto {
        val now = ZonedDateTime.now(ZoneId.of("UTC"))
            .withMinute(0)
            .withSecond(0)
            .withNano(0)

        val times = listOf(
            now.minusHours(3),
            now.minusHours(2),
            now.minusHours(1),
            now,
            now.plusHours(1),
            now.plusHours(2),
            now.plusHours(3),
        ).map { it.toLocalDateTime().toString() }

        return OpenMeteoForecastDto(
            latitude = 43.8,
            longitude = 6.2,
            timezone = "UTC",
            hourly = HourlyForecastDto(
                time = times,
                precipitation = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 9.0),
                rain = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 9.0),
                showers = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                precipitationProbability = listOf(1, 2, 3, 4, 5, 6, 7),
                weatherCode = listOf(0, 0, 1, 2, 3, 4, 5),
            ),
        )
    }

    private fun canyonDetail(
        watershed: CanyonWatershed? = null,
        geoPoints: List<GeoPoint> = listOf(
            GeoPoint(id = 1, canyonId = 42, type = GeoPointType.PARKING_AVAL, latitude = 43.0, longitude = 6.0),
        ),
    ): CanyonDetail = CanyonDetail(
        canyon = Canyon(
            id = 42,
            nom = "Riolan",
            nomComplet = "Canyon du Riolan",
            pays = "France",
            commune = "Sigale",
            cotation = "v4a4III",
            url = "/canyoning/canyon/42/riolan.html",
        ),
        geoPoints = geoPoints,
        watershed = watershed,
    )

    private fun fakeRemoteSource(response: OpenMeteoForecastDto): OpenMeteoRemoteSource =
        object : OpenMeteoRemoteSource(mockk<ApiHttpClient>()) {
            override suspend fun fetchForecast(latitude: Double, longitude: Double): OpenMeteoForecastDto = response
        }
}
