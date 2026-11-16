package io.github.aedev.flow.sync2.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** The network this device's traffic uses now, then again each time it changes; null while there is none. */
fun defaultNetwork(context: Context): Flow<Network?> =
    callbackFlow {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        trySend(connectivity.activeNetwork)
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(network)
                }

                override fun onLost(network: Network) {
                    trySend(connectivity.activeNetwork?.takeIf { it != network })
                }
            }
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
