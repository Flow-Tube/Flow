package io.github.aedev.flow.ui.startup

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import androidx.core.splashscreen.SplashScreen

private const val MAX_HOLD_MS = 2_500L

/**
 * The platform splash, held until the first real screen can be drawn, so a cold start goes from the
 * splash straight to content with no blank frame between. The system plays its own exit: a custom
 * exit listener makes Android hand the splash to the app's main thread, which is still busy with
 * startup, and kept the splash up for seconds longer on device. [MAX_HOLD_MS] caps the hold so a
 * stalled start can never keep the app hidden.
 */
class SplashController(
    private val activity: Activity,
) {
    @Volatile
    var contentReady: Boolean = false
        set(value) {
            if (value && !field) RestartSpike.contentShown(activity)
            field = value
        }

    private val startedAt = SystemClock.uptimeMillis()

    fun install(splash: SplashScreen) {
        splash.setKeepOnScreenCondition { !contentReady && SystemClock.uptimeMillis() - startedAt < MAX_HOLD_MS }
    }

    /** Opens the next launch on [tone] with the art of [iconSuffix]; Android 13 and later only. */
    fun rememberTheme(
        tone: SplashTone,
        iconSuffix: String,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.splashScreen.setSplashScreenTheme(splashThemeFor(iconSuffix, tone))
        }
    }
}
