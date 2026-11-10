package io.github.aedev.flow.ui.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect

/**
 * Whether battery saver is on. The system's broadcast is listened to only while the screen is
 * started, and the value is read again on each start, so a change made in the background is not missed.
 */
@Composable
fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current.applicationContext
    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    var saving by remember(powerManager) { mutableStateOf(powerManager?.isPowerSaveMode == true) }
    LifecycleStartEffect(powerManager) {
        saving = powerManager?.isPowerSaveMode == true
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    saving = powerManager?.isPowerSaveMode == true
                }
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onStopOrDispose { context.unregisterReceiver(receiver) }
    }
    return saving
}
