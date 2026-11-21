package io.github.aedev.flow.player.preview

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.PlaybackTarget
import io.github.aedev.flow.player.dlna.DlnaCastManager
import io.github.aedev.flow.utils.isWifiOrEthernet
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** The network as a preview sees it: whether it is Wi-Fi or wired, and whether it is too slow for a short dwell. */
internal data class PreviewNetwork(
    val onWifi: Boolean,
    val slow: Boolean,
)

/**
 * Everything outside Home that decides whether a feed preview may play. Each flow registers its
 * system listener only while it is collected, so nothing listens while Home is gone or the setting
 * is off.
 */
internal class FeedPreviewConditions
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        val lowRamDevice: Boolean by lazy {
            context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
        }

        fun network(): Flow<PreviewNetwork> =
            callbackFlow {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                if (manager == null) {
                    trySend(PreviewNetwork(onWifi = false, slow = false))
                    awaitClose()
                    return@callbackFlow
                }
                val callback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onCapabilitiesChanged(
                            network: Network,
                            capabilities: NetworkCapabilities,
                        ) {
                            trySend(capabilities.toPreviewNetwork())
                        }

                        override fun onLost(network: Network) {
                            trySend(PreviewNetwork(onWifi = false, slow = false))
                        }
                    }
                trySend(
                    runCatching {
                        manager
                            .getNetworkCapabilities(
                                manager.activeNetwork,
                            ).toPreviewNetwork()
                    }.getOrDefault(PreviewNetwork(false, false)),
                )
                runCatching { manager.registerDefaultNetworkCallback(callback) }
                awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
            }.distinctUntilChanged()

        /** TalkBack and other touch-exploration services, which the reference also plays nothing for. */
        fun accessibilityOn(): Flow<Boolean> =
            callbackFlow {
                val manager = context.getSystemService(AccessibilityManager::class.java)
                if (manager == null) {
                    trySend(false)
                    awaitClose()
                    return@callbackFlow
                }
                val listener = AccessibilityManager.TouchExplorationStateChangeListener { trySend(it) }
                trySend(manager.isTouchExplorationEnabled)
                manager.addTouchExplorationStateChangeListener(listener)
                awaitClose { manager.removeTouchExplorationStateChangeListener(listener) }
            }.distinctUntilChanged()

        fun batterySaver(): Flow<Boolean> =
            callbackFlow {
                val manager = context.getSystemService(PowerManager::class.java)
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(
                            context: Context,
                            intent: Intent,
                        ) {
                            trySend(manager?.isPowerSaveMode == true)
                        }
                    }
                trySend(manager?.isPowerSaveMode == true)
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                awaitClose { context.unregisterReceiver(receiver) }
            }.distinctUntilChanged()

        fun thermalSevere(): Flow<Boolean> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return flowOf(false)
            return callbackFlow {
                val manager = context.getSystemService(PowerManager::class.java)
                if (manager == null) {
                    trySend(false)
                    awaitClose()
                    return@callbackFlow
                }
                val listener = PowerManager.OnThermalStatusChangedListener { trySend(it >= PowerManager.THERMAL_STATUS_SEVERE) }
                trySend(manager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE)
                manager.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
                awaitClose { manager.removeThermalStatusListener(listener) }
            }.distinctUntilChanged()
        }

        /** A video loaded in the player or mini player, or anything playing in either player. */
        fun mainPlayerActive(): Flow<Boolean> =
            combine(
                GlobalPlayerState.playerState.map { it.isPlaying }.distinctUntilChanged(),
                GlobalPlayerState.currentVideo.map { it != null }.distinctUntilChanged(),
                EnhancedMusicPlayerManager.playerState.map { it.isPlaying }.distinctUntilChanged(),
            ) { videoPlaying, videoLoaded, musicPlaying -> videoPlaying || videoLoaded || musicPlaying }
                .distinctUntilChanged()

        /** A DLNA renderer, or another device this one drives ("Play on"); the latter has no flow and is read on each change. */
        fun casting(): Flow<Boolean> =
            DlnaCastManager.currentDevice.map { it != null || PlaybackTarget.remote != null }.distinctUntilChanged()

        private fun NetworkCapabilities?.toPreviewNetwork(): PreviewNetwork {
            val kbps = this?.linkDownstreamBandwidthKbps ?: 0
            return PreviewNetwork(onWifi = isWifiOrEthernet(), slow = kbps in 1 until SLOW_LINK_KBPS)
        }

        private companion object {
            const val SLOW_LINK_KBPS = 1_000
        }
    }
