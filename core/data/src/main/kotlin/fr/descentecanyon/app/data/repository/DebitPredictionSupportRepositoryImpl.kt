package fr.descentecanyon.app.data.repository

import fr.descentecanyon.app.data.local.user.dao.DailyWeatherDao
import fr.descentecanyon.app.data.local.user.entity.DailyWeatherEntity
import fr.descentecanyon.app.data.network.retryOnConnectivityFailure
import fr.descentecanyon.app.data.remote.scraper.CanyonScraper
import fr.descentecanyon.app.data.remote.weather.OpenMeteoDailyResponseDto
import fr.descentecanyon.app.data.remote.weather.OpenMeteoRemoteSource
import fr.descentecanyon.app.data.remote.weather.RainGaugeSource
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.domain.coroutines.IoDispatcher
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import fr.descentecanyon.app.domain.error.suspendRunCatching
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.domain.model.DailyWeatherSource
import fr.descentecanyon.app.domain.model.DailyWeatherValue
import fr.descentecanyon.app.domain.model.DebitPredictionSupport
import fr.descentecanyon.app.domain.model.RecentDebitCell
import fr.descentecanyon.app.domain.model.WeatherGridInfo
import fr.descentecanyon.app.domain.model.WeatherTarget
import fr.descentecanyon.app.domain.repository.DebitPredictionSupportRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class DebitPredictionSupportRepositoryImpl @Inject constructor(
    private val remoteSource: OpenMeteoRemoteSource,
    private val dailyWeatherDao: DailyWeatherDao,
    private val runtimeLookupStore: EmbeddedDebitRuntimeLookupStore,
    private val connectivityObserver: ConnectivityObserver,
    private val scraper: CanyonScraper,
    private val rainSource: RainGaugeSource,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DebitPredictionSupportRepository {

    private val recentCellsMutex = Mutex()

    @Volatile
    private var cachedRecentCells: Pair<Long, List<RecentDebitCell>>? = null

    override suspend fun getPredictionSupport(detail: CanyonDetail): Result<DebitPredictionSupport> = withContext(ioDispatcher) {
        suspendRunCatching {
            val target = WeatherTargetResolver.resolve(detail)
                ?: appError(AppError.DataUnavailable)
            val dailyWeather = loadDailyWeather(detail.canyon.id, target)
            val runtimeLookup = runtimeLookupStore.resolve(detail.canyon)
            DebitPredictionSupport(
                target = target,
                timezone = dailyWeather.timezone,
                fetchedAt = dailyWeather.fetchedAt,
                dailyWeather = dailyWeather.dailyWeather,
                runtimeLookup = runtimeLookup,
                usedWeatherCache = dailyWeather.usedCache,
                weatherGrid = dailyWeather.grid,
                recentCells = loadRecentCells(),
                stationRain = loadStationRain(runtimeLookup.rainStations.map { it.code }, dailyWeather.timezone),
                automaticRain = loadAutomaticRain(runtimeLookup.rainStations.map { it.code }, dailyWeather.timezone),
            )
        }
    }

    /** Rain gauge rainfall of the last days (Météo-France, MeteoSwiss; empty offline or when unavailable). */
    private suspend fun loadStationRain(stationCodes: List<String>, timezone: String): Map<String, Map<LocalDate, Double>> {
        if (stationCodes.isEmpty() || !connectivityObserver.isCurrentlyOnline()) return emptyMap()
        return suspendRunCatching { rainSource.dailyRain(stationCodes, LocalDate.now(timezone.toZoneIdOrUtc())) }.getOrDefault(emptyMap())
    }

    /** Yesterday's rain at the automatic gauges (hourly Météo-France file, MeteoSwiss). */
    private suspend fun loadAutomaticRain(stationCodes: List<String>, timezone: String): Map<String, Double> {
        if (stationCodes.isEmpty() || !connectivityObserver.isCurrentlyOnline()) return emptyMap()
        return suspendRunCatching {
            rainSource.yesterdayAutomaticRain(stationCodes, LocalDate.now(timezone.toZoneIdOrUtc()))
        }.getOrDefault(emptyMap())
    }

    /**
     * Recent reports of every canyon from the site's "derniers débits" day grid (one request,
     * cached for [RECENT_CELLS_TTL_MS]). Empty when offline or unavailable: the model then works
     * without the neighbour features.
     */
    private suspend fun loadRecentCells(): List<RecentDebitCell> {
        val now = System.currentTimeMillis()
        cachedRecentCells?.takeIf { now - it.first < RECENT_CELLS_TTL_MS }?.let { return it.second }
        if (!connectivityObserver.isCurrentlyOnline()) return cachedRecentCells?.second.orEmpty()
        return recentCellsMutex.withLock {
            cachedRecentCells?.takeIf { now - it.first < RECENT_CELLS_TTL_MS }?.let { return@withLock it.second }
            scraper.scrapeLatestDebitGrid().getOrNull()
                ?.also { cachedRecentCells = System.currentTimeMillis() to it }
                ?: cachedRecentCells?.second.orEmpty()
        }
    }

    /**
     * Loads the daily weather window used by the debit model: ERA5 reanalysis (the training source)
     * for the past, completed by ECMWF IFS 0.25 deg for the last days ERA5 does not cover yet and for
     * the forecast. ERA5 days are cached and only the recent days are refetched.
     */
    private suspend fun loadDailyWeather(canyonId: Int, target: WeatherTarget): DailyWeatherLoadResult {
        if (!connectivityObserver.isCurrentlyOnline()) {
            return loadCachedFallback(canyonId)
                ?: appError(AppError.Offline)
        }

        val forecastResult = suspendRunCatching {
            retryOnConnectivityFailure {
                remoteSource.fetchDebitForecast(
                    latitude = target.latitude,
                    longitude = target.longitude,
                    pastDays = FORECAST_PAST_DAYS,
                    forecastDays = FORECAST_DAYS,
                )
            }
        }
        if (forecastResult.isFailure) {
            return loadCachedFallback(canyonId)
                ?: throw forecastResult.exceptionOrNull() ?: IllegalStateException("Météo journalière indisponible")
        }
        val forecast = forecastResult.getOrThrow()
        val zoneId = forecast.timezone.toZoneIdOrUtc()
        val today = LocalDate.now(zoneId)
        val windowStart = today.minusDays(WINDOW_DAYS - 1)
        val yesterday = today.minusDays(1)
        val stableArchiveEnd = today.minusDays(ARCHIVE_REFRESH_DAYS)

        val cachedRows = dailyWeatherDao.getByCanyonIdAndDateRange(
            canyonId = canyonId,
            startDate = windowStart.toString(),
            endDate = today.plusDays(FORECAST_DAYS.toLong() - 1).toString(),
        ).map { it.toDomain() }
        val cachedArchive = cachedRows.filter { it.source == DailyWeatherSource.ARCHIVE && it.isComplete() }
        val archiveStart = if (hasCompleteCoverage(cachedArchive, windowStart, stableArchiveEnd)) {
            stableArchiveEnd.plusDays(1)
        } else {
            windowStart
        }
        val archiveRows = suspendRunCatching {
            retryOnConnectivityFailure {
                remoteSource.fetchDebitArchive(
                    latitude = target.latitude,
                    longitude = target.longitude,
                    startDate = archiveStart.toString(),
                    endDate = yesterday.toString(),
                )
            }.toDailyValues(DailyWeatherSource.ARCHIVE).filter { it.isComplete() }
        }.getOrNull()

        val merged = LinkedHashMap<LocalDate, DailyWeatherValue>()
        cachedRows.forEach { merged[it.date] = it }
        forecast.toDailyValues(DailyWeatherSource.FORECAST)
            .filter { it.isComplete() }
            .forEach { row ->
                val existing = merged[row.date]
                if (existing == null || existing.source != DailyWeatherSource.ARCHIVE || !existing.isComplete()) {
                    merged[row.date] = row
                }
            }
        archiveRows?.forEach { merged[it.date] = it }
        val mergedRows = merged.values.sortedBy { it.date }

        val fetchedAt = Instant.now()
        dailyWeatherDao.insertAll(mergedRows.map { row -> row.toEntity(canyonId, forecast.timezone, target, fetchedAt) })
        dailyWeatherDao.deleteBefore(canyonId, today.minusDays(PRUNE_AFTER_DAYS).toString())

        return DailyWeatherLoadResult(
            timezone = forecast.timezone,
            fetchedAt = fetchedAt,
            dailyWeather = mergedRows,
            usedCache = archiveRows == null,
            grid = WeatherGridInfo(forecast.latitude, forecast.longitude, forecast.elevation),
        )
    }

    private suspend fun loadCachedFallback(canyonId: Int): DailyWeatherLoadResult? {
        val today = LocalDate.now()
        val rows = dailyWeatherDao.getByCanyonIdAndDateRange(
            canyonId = canyonId,
            startDate = today.minusDays(WINDOW_DAYS + 2).toString(),
            endDate = today.plusDays(FORECAST_DAYS.toLong() - 1).toString(),
        )
        if (rows.isEmpty()) {
            return null
        }
        return DailyWeatherLoadResult(
            timezone = rows.first().timezone,
            fetchedAt = rows.maxOfOrNull { it.fetchedAtEpochMs }?.let(Instant::ofEpochMilli) ?: Instant.EPOCH,
            dailyWeather = rows.map { it.toDomain() },
            usedCache = true,
            grid = null,
        )
    }

    private fun DailyWeatherValue.isComplete(): Boolean = precipitationSum != null && temperature2mMean != null

    private fun OpenMeteoDailyResponseDto.toDailyValues(source: DailyWeatherSource): List<DailyWeatherValue> {
        val dailyRows = daily ?: return emptyList()
        return dailyRows.time.mapIndexed { index, rawDate ->
            DailyWeatherValue(
                date = LocalDate.parse(rawDate),
                precipitationSum = dailyRows.precipitationSum.getOrNull(index),
                temperature2mMean = dailyRows.temperature2mMean.getOrNull(index),
                source = source,
            )
        }
    }

    private fun DailyWeatherValue.toEntity(
        canyonId: Int,
        timezone: String,
        target: WeatherTarget,
        fetchedAt: Instant,
    ): DailyWeatherEntity = DailyWeatherEntity(
        canyonId = canyonId,
        date = date.toString(),
        timezone = timezone,
        targetLatitude = target.latitude,
        targetLongitude = target.longitude,
        targetSource = target.source.name,
        sourceKind = source.name,
        precipitationSum = precipitationSum,
        rainSum = rainSum,
        snowfallSum = snowfallSum,
        temperature2mMean = temperature2mMean,
        temperature2mMin = temperature2mMin,
        temperature2mMax = temperature2mMax,
        precipitationHours = precipitationHours,
        fetchedAtEpochMs = fetchedAt.toEpochMilli(),
    )

    private fun DailyWeatherEntity.toDomain(): DailyWeatherValue = DailyWeatherValue(
        date = LocalDate.parse(date),
        precipitationSum = precipitationSum,
        rainSum = rainSum,
        snowfallSum = snowfallSum,
        temperature2mMean = temperature2mMean,
        temperature2mMin = temperature2mMin,
        temperature2mMax = temperature2mMax,
        precipitationHours = precipitationHours,
        source = DailyWeatherSource.valueOf(sourceKind),
    )

    private fun hasCompleteCoverage(rows: List<DailyWeatherValue>, start: LocalDate, end: LocalDate): Boolean {
        var current = start
        val availableDates = rows.map { it.date }.toSet()
        while (!current.isAfter(end)) {
            if (current !in availableDates) {
                return false
            }
            current = current.plusDays(1)
        }
        return true
    }

    private fun String.toZoneIdOrUtc(): ZoneId = runCatching { ZoneId.of(this) }.getOrDefault(ZoneId.of("UTC"))

    private data class DailyWeatherLoadResult(
        val timezone: String,
        val fetchedAt: Instant,
        val dailyWeather: List<DailyWeatherValue>,
        val usedCache: Boolean,
        val grid: WeatherGridInfo?,
    )

    companion object {
        private const val WINDOW_DAYS = DebitHydroModel.WINDOW_DAYS.toLong()
        private const val RECENT_CELLS_TTL_MS = 30L * 60L * 1000L
        private const val FORECAST_DAYS = 3
        private const val FORECAST_PAST_DAYS = 14
        private const val ARCHIVE_REFRESH_DAYS = 12L
        private const val PRUNE_AFTER_DAYS = WINDOW_DAYS + 30L
    }
}
