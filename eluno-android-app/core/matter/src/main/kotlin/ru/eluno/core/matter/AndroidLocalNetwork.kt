package ru.eluno.core.matter

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/** Observe local transports without requiring INTERNET or VALIDATED (offline LAN is supported). */
fun localNetworks(context: Context) = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val present = mutableSetOf<Network>()
    var generation = 0L
    val callback = object : ConnectivityManager.NetworkCallback() {
        @Synchronized override fun onAvailable(network: Network) { present += network; publish() }
        @Synchronized override fun onLost(network: Network) { present -= network; publish() }
        @Synchronized override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { present += network; publish() }
        private fun publish() { trySend(LocalNetwork(present.isNotEmpty(), ++generation)) }
    }
    trySend(LocalNetwork(false))
    manager.registerNetworkCallback(NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}
