package fr.descentecanyon.app.data.remote.backend

import fr.descentecanyon.app.data.BackendConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer

/**
 * A MockWebServer behind an https:// configuration: the client rewrites every request to the plain
 * HTTP mock, so the production check that the server address is https stays untouched.
 */
class BackendTestServer(publicKeys: List<String> = listOf("unused"), embeddedCatalogVersion: String = "2026-10-27T17:26:31.659642Z") {
    val server = MockWebServer().apply { start() }

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    val config = BackendConfig(
        baseUrl = "https://api.example.org",
        publicKeys = publicKeys,
        catalogSchemaVersion = 4,
        catalogIdentityHash = IDENTITY,
        embeddedCatalogVersion = embeddedCatalogVersion,
    )

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val original = chain.request()
            val target = original.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
            chain.proceed(original.newBuilder().url(target).build())
        }
        .build()

    val client = BackendClient(httpClient, json, config, Dispatchers.Unconfined)

    fun shutdown() = server.shutdown()

    companion object {
        const val IDENTITY = "38ceb224a4367c7742d73e390e93467c"

        fun resource(path: String): String = requireNotNull(BackendTestServer::class.java.classLoader?.getResource(path)) {
            path
        }.readText()
    }
}
