package io.github.aedev.flow.ui.utils

import android.os.Build
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
 * Whether the system reports the device warm enough to start throttling (moderate or worse), so
 * optional work can ease off before it gets hotter. Listened to only while started; always false
 * before Android 10, which reports no thermal status.
 */
@Composable
fun rememberThermalThrottling(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val context = LocalContext.current.applicationContext
    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    var hot by remember(powerManager) { mutableStateOf(powerManager.isWarm()) }
    LifecycleStartEffect(powerManager) {
        hot = powerManager.isWarm()
        val listener = PowerManager.OnThermalStatusChangedListener { status -> hot = status >= PowerManager.THERMAL_STATUS_MODERATE }
        powerManager?.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
        onStopOrDispose { powerManager?.removeThermalStatusListener(listener) }
    }
    return hot
}

private fun PowerManager?.isWarm(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (this?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_MODERATE
