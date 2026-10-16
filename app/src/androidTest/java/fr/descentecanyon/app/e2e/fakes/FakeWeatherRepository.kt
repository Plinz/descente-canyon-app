package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.CanyonWeather
import fr.descentecanyon.app.domain.model.WeatherLocationSource
import fr.descentecanyon.app.domain.model.WeatherTarget
import fr.descentecanyon.app.domain.repository.WeatherRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeWeatherRepository @Inject constructor() : WeatherRepository {
    override suspend fun getCachedCanyonWeather(canyonId: Int): CanyonWeather? = null

    override suspend fun getCanyonWeather(detail: CanyonDetail): Result<CanyonWeather> = Result.success(
        CanyonWeather(
            target = WeatherTarget(
                latitude = 0.0,
                longitude = 0.0,
                source = WeatherLocationSource.UNKNOWN,
            ),
            timezone = "UTC",
            fetchedAt = Instant.EPOCH,
        ),
    )
}
