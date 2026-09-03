package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.dao.DailyWeatherDao
import fr.descentecanyon.app.data.local.entity.DailyWeatherEntity
import fr.descentecanyon.app.data.remote.weather.DailyForecastDto
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
import io.ktor.client.HttpClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.net.UnknownHostException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherRepositoryImplTest {

    @Test
    fun `uses watershed center when available and inserts daily weather into cache`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dailyWeatherDao = mockk<DailyWeatherDao>(relaxed = true)
        val remoteSource = fakeRemoteSource(forecastDto())
        val repository = WeatherRepositoryImpl(remoteSource, dailyWeatherDao, dispatcher)
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
        assertEquals(2, weather.dailyForecasts.size)
        assertEquals(0, weather.dailyForecasts.first().weatherCode)

        coVerify { dailyWeatherDao.insertAll(any()) }
    }

    @Test
    fun `falls back to entry point when watershed is missing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dailyWeatherDao = mockk<DailyWeatherDao>(relaxed = true)
        val remoteSource = fakeRemoteSource(forecastDto())
        val repository = WeatherRepositoryImpl(remoteSource, dailyWeatherDao, dispatcher)
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

    @Test
    fun `falls back to cached daily weather when network fails`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dailyWeatherDao = mockk<DailyWeatherDao>()
        val cachedEntities = listOf(
            DailyWeatherEntity(
                canyonId = 42,
                date = LocalDate.now().toString(),
                timezone = "UTC",
                targetLatitude = 43.0,
                targetLongitude = 6.0,
                targetSource = "ENTRY",
                sourceKind = "OPEN_METEO",
                precipitationSum = 5.0,
                temperature2mMin = 12.0,
                temperature2mMax = 22.0,
                weatherCode = 0,
                fetchedAtEpochMs = System.currentTimeMillis(),
            )
        )
        coEvery { dailyWeatherDao.getByCanyonIdAndDateRange(eq(42), any(), any()) } returns cachedEntities

        val failingRemoteSource = object : OpenMeteoRemoteSource(mockk<HttpClient>()) {
            override suspend fun fetchForecast(latitude: Double, longitude: Double): OpenMeteoForecastDto {
                throw UnknownHostException("Network down")
            }
        }

        val repository = WeatherRepositoryImpl(failingRemoteSource, dailyWeatherDao, dispatcher)
        val detail = canyonDetail()

        val result = repository.getCanyonWeather(detail)

        assertTrue(result.isSuccess)
        val weather = result.getOrThrow()
        assertEquals(1, weather.dailyForecasts.size)
        assertEquals(5.0, weather.dailyForecasts.first().precipitationMm, 0.001)
        assertEquals(0, weather.dailyForecasts.first().weatherCode)
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
            daily = DailyForecastDto(
                time = listOf(LocalDate.now().toString(), LocalDate.now().plusDays(1).toString()),
                precipitationSum = listOf(2.5, 0.0),
                temperature2mMin = listOf(10.0, 12.0),
                temperature2mMax = listOf(20.0, 24.0),
                weatherCode = listOf(0, 1),
            )
        )
    }

    private fun canyonDetail(
        watershed: CanyonWatershed? = null,
        geoPoints: List<GeoPoint> = listOf(
            GeoPoint(id = 1, canyonId = 42, type = GeoPointType.PARKING_AVAL, latitude = 43.0, longitude = 6.0),
        ),
    ): CanyonDetail {
        return CanyonDetail(
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
    }

    private fun fakeRemoteSource(response: OpenMeteoForecastDto): OpenMeteoRemoteSource {
        return object : OpenMeteoRemoteSource(mockk<HttpClient>()) {
            override suspend fun fetchForecast(
                latitude: Double,
                longitude: Double,
            ): OpenMeteoForecastDto {
                return response
            }
        }
    }
}
