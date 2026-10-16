package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.network.ApiHttpClient
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
import fr.descentecanyon.app.domain.model.WeatherCondition
import fr.descentecanyon.app.domain.model.WeatherLocationSource
import fr.descentecanyon.app.testutil.InMemoryKeyValueDao
import io.mockk.mockk
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherRepositoryImplTest {

    private val now = LocalDateTime.of(2026, 7, 14, 10, 0)
    private val clock = clockAt(now)
    private val keyValueDao = InMemoryKeyValueDao()

    @Test
    fun `uses watershed center when available`() = runTest {
        val repository = repository(fakeRemoteSource(forecastDto()))
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
        val rain = weather.rainAround(now)
        assertEquals(10.0, rain.pastTotalMm, 0.001)
        assertEquals(20.0, rain.nextTotalMm, 0.001)
        assertEquals(7, rain.maxProbabilityPercent)
        assertFalse(weather.isFromCache)
    }

    @Test
    fun `falls back to entry point when watershed is missing`() = runTest {
        val repository = repository(fakeRemoteSource(forecastDto()))
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
    fun `daily forecast keeps today and the next days with sun times and storms`() = runTest {
        val repository = repository(fakeRemoteSource(forecastDto()))

        val weather = repository.getCanyonWeather(canyonDetail()).getOrThrow()

        assertEquals(listOf(LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 15)), weather.daily.map { it.date })
        val today = weather.daily.first()
        assertEquals(LocalTime.of(6, 4), today.sunrise)
        assertEquals(LocalTime.of(21, 30), today.sunset)
        assertEquals(31.5, today.temperatureMaxC!!, 0.001)
        assertEquals(WeatherCondition.THUNDERSTORM, weather.daily[1].condition)
        assertEquals(LocalDate.of(2026, 7, 15), weather.nextThunderstorm(now)?.date)
    }

    @Test
    fun `the last forecast is kept for offline use and split at the current hour`() = runTest {
        repository(fakeRemoteSource(forecastDto())).getCanyonWeather(canyonDetail()).getOrThrow()

        // Two hours later, without network.
        val later = repository(failingRemoteSource(), clockAt(now.plusHours(2)))
        val cached = later.getCachedCanyonWeather(42)!!

        assertTrue(cached.isFromCache)
        assertEquals(WeatherLocationSource.DOWNSTREAM_PARKING, cached.target.source)
        // Hours 07:00 to 12:00 are now in the past (1 + 2 + 3 + 4 + 5 + 6 mm).
        val rain = cached.rainAround(now.plusHours(2))
        assertEquals(21.0, rain.pastTotalMm, 0.001)
        assertEquals(9.0, rain.nextTotalMm, 0.001)
    }

    @Test
    fun `a cached forecast older than three days is not shown`() = runTest {
        repository(fakeRemoteSource(forecastDto())).getCanyonWeather(canyonDetail()).getOrThrow()

        val muchLater = repository(failingRemoteSource(), clockAt(now.plusDays(4)))

        assertNull(muchLater.getCachedCanyonWeather(42))
        assertNull(muchLater.getCachedCanyonWeather(43))
    }

    private fun TestScope.repository(remoteSource: OpenMeteoRemoteSource, clock: Clock = this@WeatherRepositoryImplTest.clock) =
        WeatherRepositoryImpl(remoteSource, keyValueDao, clock, StandardTestDispatcher(testScheduler))

    private fun forecastDto(): OpenMeteoForecastDto {
        val times = (-3..3).map { offset -> now.plusHours(offset.toLong()).toString() }

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
                time = listOf("2026-07-13", "2026-07-14", "2026-07-15"),
                weatherCode = listOf(61, 1, 95),
                precipitationSum = listOf(12.0, 0.0, 25.0),
                precipitationProbabilityMax = listOf(80, 5, 70),
                temperature2mMax = listOf(24.0, 31.5, 22.0),
                temperature2mMin = listOf(14.0, 16.0, 15.0),
                sunrise = listOf("2026-07-13T06:03", "2026-07-14T06:04", "2026-07-15T06:05"),
                sunset = listOf("2026-07-13T21:31", "2026-07-14T21:30", "2026-07-15T21:30"),
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

    private fun failingRemoteSource(): OpenMeteoRemoteSource = object : OpenMeteoRemoteSource(mockk<ApiHttpClient>()) {
        override suspend fun fetchForecast(latitude: Double, longitude: Double): OpenMeteoForecastDto = throw IOException("offline")
    }

    private fun clockAt(dateTime: LocalDateTime): Clock = Clock.fixed(dateTime.toInstant(ZoneOffset.UTC), ZoneId.of("UTC"))
}
