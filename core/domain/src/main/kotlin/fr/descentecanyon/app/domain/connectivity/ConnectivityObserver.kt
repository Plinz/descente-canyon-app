package fr.descentecanyon.app.domain.connectivity

import kotlinx.coroutines.flow.Flow

/** Whether the device can currently reach the internet. */
interface ConnectivityObserver {
    fun observe(): Flow<Boolean>
    fun isCurrentlyOnline(): Boolean
}
