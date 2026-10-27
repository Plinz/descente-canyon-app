package fr.descentecanyon.app.data.remote.geocoding

import fr.descentecanyon.app.data.network.ApiHttpClient
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Places by name or address from the Photon service of komoot on OpenStreetMap data, which needs no
 * key and is made for search as you type. Its fair use rules out bulk requests: the app asks only
 * once the user stops typing, one search at a time.
 */
@Singleton
open class PhotonRemoteSource @Inject constructor(private val httpClient: ApiHttpClient) {

    /** [language] must be one of [LANGUAGES] (the service refuses the others); null gives local names. */
    open suspend fun search(query: String, latitude: Double?, longitude: Double?, language: String?): PhotonResponseDto {
        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", LIMIT.toString())
            .apply {
                if (latitude != null && longitude != null) {
                    addQueryParameter("lat", String.format(Locale.ROOT, "%.4f", latitude))
                    addQueryParameter("lon", String.format(Locale.ROOT, "%.4f", longitude))
                }
                language?.let { addQueryParameter("lang", it) }
            }
            .build()
        return httpClient.getJson(url, PhotonResponseDto.serializer())
    }

    companion object {
        const val SEARCH_URL = "https://photon.komoot.io/api/"

        /** The languages the service gives names in. */
        val LANGUAGES = setOf("fr", "en", "de")

        private const val LIMIT = 8
    }
}

@Serializable
data class PhotonResponseDto(val features: List<PhotonFeatureDto> = emptyList())

@Serializable
data class PhotonFeatureDto(val geometry: PhotonGeometryDto, val properties: PhotonPropertiesDto = PhotonPropertiesDto())

/** Longitude then latitude. */
@Serializable
data class PhotonGeometryDto(val coordinates: List<Double> = emptyList())

@Serializable
data class PhotonPropertiesDto(
    val name: String? = null,
    @SerialName("osm_key") val osmKey: String? = null,
    @SerialName("osm_value") val osmValue: String? = null,
    val type: String? = null,
    val housenumber: String? = null,
    val street: String? = null,
    val postcode: String? = null,
    val city: String? = null,
    val locality: String? = null,
    val district: String? = null,
    val county: String? = null,
    val country: String? = null,
)
