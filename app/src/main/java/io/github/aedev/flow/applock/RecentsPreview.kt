package io.github.aedev.flow.applock

import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Blanks this activity's preview in recent apps while the app lock and Hide in recent apps are on.
 * Below Android 13 the only way is FLAG_SECURE, which also blocks screenshots.
 */
internal fun ComponentActivity.hideFromRecentsWhenAsked(appLock: AppLock) {
    lifecycleScope.launch {
        appLock.settings
            .filterNotNull()
            .map { it.enabled && it.hideInRecents }
            .distinctUntilChanged()
            .collect { hide ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setRecentsScreenshotEnabled(!hide)
                } else if (hide) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
    }
}
