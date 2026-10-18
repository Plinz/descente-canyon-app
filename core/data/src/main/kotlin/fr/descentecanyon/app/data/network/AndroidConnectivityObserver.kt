package fr.descentecanyon.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.descentecanyon.app.domain.connectivity.ConnectivityObserver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Online means that the default network, the one requests go through, reaches the internet:
 * Android checked it ("validated"). A Wi-Fi without internet (gîte, captive portal) or a dead mobile
 * connection counts as offline, so the app shows what it saved at once instead of waiting for
 * timeouts.
 */
@Singleton
class AndroidConnectivityObserver @Inject constructor(@param:ApplicationContext private val context: Context) : ConnectivityObserver {

    override fun observe(): Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        trySend(isCurrentlyOnline())

        // Only the default network: another network appearing or vanishing says nothing about it.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(networkCapabilities.reachesInternet())
            }

            override fun onLost(network: Network) {
                trySend(isCurrentlyOnline())
            }
        }

        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    override fun isCurrentlyOnline(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.reachesInternet() == true
    }

    private fun NetworkCapabilities.reachesInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
