package fr.descentecanyon.app.data.network

import javax.inject.Qualifier

/** The [okhttp3.OkHttpClient] bound to [SiteCookieJar], used only for descente-canyon.com. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SiteHttp
