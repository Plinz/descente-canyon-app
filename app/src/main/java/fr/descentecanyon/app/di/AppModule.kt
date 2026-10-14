package fr.descentecanyon.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.BuildConfig
import fr.descentecanyon.app.data.AppBuildInfo

/** Values only the application module knows. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    fun provideAppBuildInfo(): AppBuildInfo = AppBuildInfo(
        versionCode = BuildConfig.VERSION_CODE,
        versionName = BuildConfig.VERSION_NAME,
    )
}
