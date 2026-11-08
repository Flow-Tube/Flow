package io.github.aedev.flow.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Applies the app's vibration preference to everything in [content].
 *
 * Turning the preference off swaps a no-op implementation in over the whole app, so every control,
 * gesture and sheet below stops vibrating without a single call site having to ask whether it
 * should (#1066, #1866).
 */
@Composable
fun AppHaptics(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val hostHaptics = LocalHapticFeedback.current
    CompositionLocalProvider(
        LocalHapticFeedback provides if (enabled) hostHaptics else SilentHapticFeedback,
        content = content,
    )
}

/** The haptic for an on/off action such as like, save or subscribe, by the state it has just switched to. */
fun toggleHapticType(nowOn: Boolean): HapticFeedbackType = if (nowOn) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff

/**
 * Stands in for the platform's haptics while the vibration preference is off.
 *
 * Compose ships exactly this as NoHapticFeedback but keeps it internal, and the interface is a
 * single method, so there is nothing to reuse and nothing to get wrong.
 */
private object SilentHapticFeedback : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}
