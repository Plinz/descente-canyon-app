package fr.descentecanyon.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.BuildConfig
import fr.descentecanyon.app.data.AppBuildInfo
import fr.descentecanyon.app.data.BackendConfig

/** Values only the application module knows. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    fun provideAppBuildInfo(): AppBuildInfo = AppBuildInfo(
        versionCode = BuildConfig.VERSION_CODE,
        versionName = BuildConfig.VERSION_NAME,
    )

    @Provides
    fun provideBackendConfig(): BackendConfig = BackendConfig(
        baseUrl = BuildConfig.BACKEND_URL,
        publicKeys = BuildConfig.BACKEND_PUBLIC_KEYS.split(',').map(String::trim).filter(String::isNotEmpty),
        catalogSchemaVersion = BuildConfig.CATALOG_SCHEMA_VERSION,
        catalogIdentityHash = BuildConfig.CATALOG_IDENTITY_HASH,
        embeddedCatalogVersion = BuildConfig.EMBEDDED_CATALOG_VERSION,
    )
}
