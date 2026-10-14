package fr.descentecanyon.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.descentecanyon.app.data.network.AndroidConnectivityObserver
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkObserverModule {

    @Binds
    @Singleton
    abstract fun bindConnectivityObserver(impl: AndroidConnectivityObserver): ConnectivityObserver
}
