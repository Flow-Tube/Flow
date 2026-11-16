package io.github.aedev.flow.applock

import android.os.Build
import java.util.concurrent.TimeUnit

/** The app lock settings, kept for the whole device. */
data class AppLockSettings(
    val enabled: Boolean,
    val delayMinutes: Int,
    val hideInRecents: Boolean,
)

const val DEFAULT_APP_LOCK_DELAY_MINUTES = 0

/** The Lock after choices, in minutes; 0 locks as soon as Flow leaves the screen. */
val APP_LOCK_DELAYS_MINUTES = listOf(0, 1, 5, 15)

/**
 * How long Flow has been away from the screen, on the elapsedRealtime clock so that changing the
 * phone's time can't shorten it. Only the first leave counts, so a PiP window that is later closed
 * doesn't restart the wait.
 */
internal class AwayClock {
    private var leftAt: Long? = null

    fun left(nowMs: Long) {
        if (leftAt == null) leftAt = nowMs
    }

    /** Whether coming back at [nowMs] needs the unlock; it also ends the time away. */
    fun returned(
        nowMs: Long,
        delayMinutes: Int,
    ): Boolean {
        val from = leftAt ?: return false
        leftAt = null
        return nowMs - from >= TimeUnit.MINUTES.toMillis(delayMinutes.toLong())
    }

    fun reset() {
        leftAt = null
    }
}

/**
 * Strong biometrics with the screen lock as the fallback, except on Android 9 and 10, which refuse
 * that pair; weak biometrics with the screen lock are what the phone's own unlock accepts there.
 */
internal fun usesStrongBiometrics(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt !in Build.VERSION_CODES.P..Build.VERSION_CODES.Q
