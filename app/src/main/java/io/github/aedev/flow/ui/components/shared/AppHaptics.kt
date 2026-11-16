package io.github.aedev.flow.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

/**
 * Applies the app's vibration preference to everything in [content], so every control, gesture and
 * sheet below stops vibrating without a single call site having to ask whether it should (#1066, #1866). With
 * [ignoreSystemSetting], they keep vibrating while the system's touch feedback is off (#1915).
 *
 * The provided instance never changes with [enabled]: LocalHapticFeedback is a static local, and swapping
 * it at the app root would recompose the whole app each time the preference loads or flips.
 */
@Composable
fun AppHaptics(
    enabled: Boolean,
    ignoreSystemSetting: Boolean,
    content: @Composable () -> Unit,
) {
    val hostHaptics = LocalHapticFeedback.current
    val view = LocalView.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentIgnoreSystem by rememberUpdatedState(ignoreSystemSetting)
    val haptics =
        remember(hostHaptics, view) {
            SwitchableHapticFeedback(
                host = hostHaptics,
                override = TouchFeedbackOverride(view),
                enabled = { currentEnabled },
                ignoreSystemSetting = { currentIgnoreSystem },
            )
        }
    CompositionLocalProvider(LocalHapticFeedback provides haptics, content = content)
}

/** The haptic for an on/off action such as like, save or subscribe, by the state it has just switched to. */
fun toggleHapticType(nowOn: Boolean): HapticFeedbackType = if (nowOn) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff

private class SwitchableHapticFeedback(
    private val host: HapticFeedback,
    private val override: TouchFeedbackOverride,
    private val enabled: () -> Boolean,
    private val ignoreSystemSetting: () -> Boolean,
) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (!enabled()) return
        if (ignoreSystemSetting() && override.perform(hapticFeedbackType)) return
        host.performHapticFeedback(hapticFeedbackType)
    }
}
