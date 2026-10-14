package fr.descentecanyon.app.data.remote.edf

import fr.descentecanyon.app.data.network.ApiHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Water release / practicability status published by EDF "Ma rivière et moi". */
@Singleton
open class EdfPracticabilityRemoteSource @Inject constructor(private val httpClient: ApiHttpClient) {
    open suspend fun fetchPracticability(practicabilityId: Long): EdfPracticabilityDto {
        val url = "$BASE_URL/api/v5/practicabilities/$practicabilityId".toHttpUrl()
        return httpClient.getJson(url, EdfPracticabilityDto.serializer())
    }

    private companion object {
        const val BASE_URL = "https://mariviereetmoi.edf.fr"
    }
}
