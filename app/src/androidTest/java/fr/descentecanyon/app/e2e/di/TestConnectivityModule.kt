package fr.descentecanyon.app.e2e.di

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import fr.descentecanyon.app.di.NetworkObserverModule
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import fr.descentecanyon.app.e2e.fakes.FakeConnectivityObserver
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [NetworkObserverModule::class],
)
object TestConnectivityModule {
    @Provides
    @Singleton
    fun provideFakeConnectivityObserver(): FakeConnectivityObserver = FakeConnectivityObserver()

    @Provides
    @Singleton
    fun provideConnectivityObserver(fake: FakeConnectivityObserver): ConnectivityObserver = fake
}
