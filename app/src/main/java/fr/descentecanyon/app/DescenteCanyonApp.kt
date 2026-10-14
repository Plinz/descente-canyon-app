package fr.descentecanyon.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import fr.descentecanyon.app.notifications.AppNotificationPublisher
import fr.descentecanyon.app.perf.PerformanceTrace
import javax.inject.Inject
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

@HiltAndroidApp
class DescenteCanyonApp : Application() {

    @Inject lateinit var appNotificationPublisher: AppNotificationPublisher

    @Inject lateinit var okHttpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        PerformanceTrace.markProcessCreated()
        appNotificationPublisher.ensureChannelsCreated()

        // Images and map tiles go through the app HTTP client (user agent, connection pool) but keep
        // their own caches, so they bypass the shared HTTP cache.
        val uncachedClient = okHttpClient.newBuilder().cache(null).build()
        // MapLibre must exist before its HTTP layer is configured (HttpRequestImpl reads it on class init).
        MapLibre.getInstance(this)
        HttpRequestUtil.setOkHttpClient(uncachedClient)
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { uncachedClient })) }
                .build()
        }
    }
}
