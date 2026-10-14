package fr.descentecanyon.app.e2e.fakes

import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class FakeConnectivityObserver @Inject constructor() : ConnectivityObserver {
    private val online = MutableStateFlow(true)

    override fun observe(): Flow<Boolean> = online.asStateFlow()

    override fun isCurrentlyOnline(): Boolean = online.value

    fun setOnline(value: Boolean) {
        online.value = value
    }
}
