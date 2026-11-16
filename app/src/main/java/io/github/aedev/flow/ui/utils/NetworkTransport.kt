package io.github.aedev.flow.ui.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.aedev.flow.utils.NetworkState
import io.github.aedev.flow.utils.isWifiOrEthernet

/**
 * Wifi (or Ethernet) or not, kept current by the platform callback. Seeded synchronously rather than defaulting
 * to false: the Shorts prefetch reads the transport synchronously too, and the two must agree or
 * they key the playback-stream cache differently and the prefetch is wasted.
 */
@Composable
fun rememberIsOnWifi(): Boolean {
    val context = LocalContext.current
    var isWifi by remember { mutableStateOf(NetworkState.isOnWifi(context)) }
    DisposableEffect(context) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        fun update() {
            isWifi = manager.getNetworkCapabilities(manager.activeNetwork).isWifiOrEthernet()
        }
        update()
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities,
                ) = update()

                override fun onLost(network: Network) = update()

                override fun onAvailable(network: Network) = update()
            }
        manager.registerDefaultNetworkCallback(callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    return isWifi
}
