package fr.descentecanyon.app.data.remote.routing

import fr.descentecanyon.app.data.network.ApiHttpClient
import fr.descentecanyon.app.domain.error.AppError
import fr.descentecanyon.app.domain.error.appError
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Car routes from the OSRM service of FOSSGIS on OpenStreetMap data, which needs no key. Its usage
 * policy allows light use only, one request per second at most: the app asks once per starting
 * place and canyon and keeps the answer.
 */
@Singleton
open class RoutingRemoteSource @Inject constructor(private val httpClient: ApiHttpClient) {

    private val mutex = Mutex()
    private var lastRequestAtMs = 0L

    /** Duration (s) and distance (m) by road. */
    open suspend fun drive(fromLatitude: Double, fromLongitude: Double, toLatitude: Double, toLongitude: Double): OsrmRouteDto =
        mutex.withLock {
            val wait = lastRequestAtMs + MIN_INTERVAL_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastRequestAtMs = System.currentTimeMillis()
            val coordinates = String.format(Locale.ROOT, "%.6f,%.6f;%.6f,%.6f", fromLongitude, fromLatitude, toLongitude, toLatitude)
            val url = "$ROUTE_URL$coordinates".toHttpUrl().newBuilder()
                .addQueryParameter("overview", "false")
                .addQueryParameter("alternatives", "false")
                .addQueryParameter("steps", "false")
                .build()
            val response = httpClient.getJson(url, OsrmResponseDto.serializer())
            response.routes.firstOrNull()?.takeIf { response.code == "Ok" } ?: appError(AppError.UnexpectedResponse)
        }

    companion object {
        const val ROUTE_URL = "https://routing.openstreetmap.de/routed-car/route/v1/driving/"
        private const val MIN_INTERVAL_MS = 1_100L
    }
}

@Serializable
data class OsrmResponseDto(val code: String, val routes: List<OsrmRouteDto> = emptyList())

@Serializable
data class OsrmRouteDto(val duration: Double, val distance: Double)
