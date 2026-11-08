package io.github.aedev.flow.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Applies the app's vibration preference to everything in [content], so every control, gesture and
 * sheet below stops vibrating without a single call site having to ask whether it should (#1066, #1866).
 *
 * The provided instance never changes with [enabled]: LocalHapticFeedback is a static local, and swapping
 * it at the app root would recompose the whole app each time the preference loads or flips.
 */
@Composable
fun AppHaptics(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val hostHaptics = LocalHapticFeedback.current
    val currentEnabled by rememberUpdatedState(enabled)
    val haptics = remember(hostHaptics) { SwitchableHapticFeedback(hostHaptics) { currentEnabled } }
    CompositionLocalProvider(LocalHapticFeedback provides haptics, content = content)
}

/** The haptic for an on/off action such as like, save or subscribe, by the state it has just switched to. */
fun toggleHapticType(nowOn: Boolean): HapticFeedbackType = if (nowOn) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff

private class SwitchableHapticFeedback(
    private val host: HapticFeedback,
    private val enabled: () -> Boolean,
) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (enabled()) host.performHapticFeedback(hapticFeedbackType)
    }
}
