package fr.descentecanyon.app.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.data.AppBuildInfo
import fr.descentecanyon.app.data.network.ApiHttpClient
import fr.descentecanyon.app.data.network.OkHttpApiHttpClient
import fr.descentecanyon.app.data.network.OkHttpSiteHttpClient
import fr.descentecanyon.app.data.network.SiteCookieJar
import fr.descentecanyon.app.data.network.SiteHttp
import fr.descentecanyon.app.data.network.SiteHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient

/**
 * The app has a single HTTP stack: one [OkHttpClient] shared by the site client, the JSON/open-data
 * client, image loading (Coil) and the map tiles (MapLibre).
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context, appBuildInfo: AppBuildInfo): OkHttpClient {
        val userAgent = "DescenteCanyonApp/${appBuildInfo.versionName} (Android)"
        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .cache(Cache(File(context.cacheDir, HTTP_CACHE_DIRECTORY), HTTP_CACHE_BYTES))
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
            }
            .build()
    }

    @Provides
    @Singleton
    @SiteHttp
    fun provideSiteOkHttpClient(base: OkHttpClient, cookieJar: SiteCookieJar): OkHttpClient = base.newBuilder().cookieJar(cookieJar).build()

    private const val HTTP_CACHE_DIRECTORY = "http-cache"
    private const val HTTP_CACHE_BYTES = 20L * 1024 * 1024
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindingsModule {

    @Binds
    @Singleton
    abstract fun bindSiteHttpClient(impl: OkHttpSiteHttpClient): SiteHttpClient

    @Binds
    @Singleton
    abstract fun bindApiHttpClient(impl: OkHttpApiHttpClient): ApiHttpClient
}
