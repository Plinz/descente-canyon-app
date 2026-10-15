package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Backed by [E2eFixtureState]: app-scoped components of earlier tests (sync scheduler...) keep running
 * in the same process, so every instance must see the same connectivity as the current test.
 */
@Singleton
class FakeConnectivityObserver @Inject constructor() : ConnectivityObserver {
    override fun observe(): Flow<Boolean> = E2eFixtureState.online.asStateFlow()

    override fun isCurrentlyOnline(): Boolean = E2eFixtureState.online.value

    fun setOnline(value: Boolean) {
        E2eFixtureState.online.value = value
    }
}
