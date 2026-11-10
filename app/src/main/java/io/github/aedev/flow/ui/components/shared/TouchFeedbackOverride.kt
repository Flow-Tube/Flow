package io.github.aedev.flow.ui.components.shared

import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat

/**
 * Plays Flow's haptics while the system's touch feedback is switched off, which otherwise mutes every
 * `performHapticFeedback` call (#1915). AndroidX has no API for this.
 *
 * Android 12 and older still honour the ignore-setting flag. From 13 apps lost that flag, but the switch only
 * silences touch-usage vibrations, so the same effect is played as a media vibration instead. That one still obeys
 * the main vibration switch, the media vibration level and battery saver.
 */
internal class TouchFeedbackOverride(
    private val view: View,
) {
    /** True when it played [type]; false leaves the call to the normal path. */
    fun perform(type: HapticFeedbackType): Boolean {
        if (systemTouchFeedbackOn()) return false
        val constant = hapticConstantFor(type) ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            return ViewCompat.performHapticFeedback(view, constant, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        }
        return vibrate(overrideVibrationFor(constant) ?: return false)
    }

    private fun systemTouchFeedbackOn(): Boolean =
        Settings.System.getInt(view.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun vibrate(vibration: OverrideVibration): Boolean {
        val vibrator =
            view.context
                .getSystemService(VibratorManager::class.java)
                ?.defaultVibrator
                ?.takeIf { it.hasVibrator() } ?: return false
        val primitive = vibration.primitive
        val effect =
            if (primitive != null && vibrator.areAllPrimitivesSupported(primitive)) {
                VibrationEffect.startComposition().addPrimitive(primitive, vibration.primitiveScale).compose()
            } else {
                VibrationEffect.createPredefined(vibration.predefined)
            }
        vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        return true
    }
}

/** An effect as Android 15 builds it for a haptic constant, limited to public effects and primitives. */
internal enum class OverrideVibration(
    val predefined: Int,
    val primitive: Int? = null,
    val primitiveScale: Float = 1f,
) {
    TICK(VibrationEffect.EFFECT_TICK),
    LIGHT_TICK(VibrationEffect.EFFECT_TICK, VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f),
    CLICK(VibrationEffect.EFFECT_CLICK),
    HEAVY_CLICK(VibrationEffect.EFFECT_HEAVY_CLICK),
    DOUBLE_CLICK(VibrationEffect.EFFECT_DOUBLE_CLICK),
    TOGGLE_ON(VibrationEffect.EFFECT_TICK, VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f),
    TOGGLE_OFF(VibrationEffect.EFFECT_TICK, VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.2f),
}

/** The constant Compose sends to the platform for [type]. */
internal fun hapticConstantFor(type: HapticFeedbackType): Int? =
    when (type) {
        HapticFeedbackType.Confirm -> HapticFeedbackConstantsCompat.CONFIRM
        HapticFeedbackType.ContextClick -> HapticFeedbackConstantsCompat.CONTEXT_CLICK
        HapticFeedbackType.GestureEnd -> HapticFeedbackConstantsCompat.GESTURE_END
        HapticFeedbackType.GestureThresholdActivate -> HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_ACTIVATE
        HapticFeedbackType.KeyboardTap -> HapticFeedbackConstantsCompat.KEYBOARD_TAP
        HapticFeedbackType.LongPress -> HapticFeedbackConstantsCompat.LONG_PRESS
        HapticFeedbackType.Reject -> HapticFeedbackConstantsCompat.REJECT
        HapticFeedbackType.SegmentFrequentTick -> HapticFeedbackConstantsCompat.SEGMENT_FREQUENT_TICK
        HapticFeedbackType.SegmentTick -> HapticFeedbackConstantsCompat.SEGMENT_TICK
        HapticFeedbackType.ToggleOff -> HapticFeedbackConstantsCompat.TOGGLE_OFF
        HapticFeedbackType.ToggleOn -> HapticFeedbackConstantsCompat.TOGGLE_ON
        HapticFeedbackType.VirtualKey -> HapticFeedbackConstantsCompat.VIRTUAL_KEY
        else -> null
    }

/** Mirrors `HapticFeedbackVibrationProvider` in Android 15; hidden effects fall back to the nearest public one. */
internal fun overrideVibrationFor(constant: Int): OverrideVibration? =
    when (constant) {
        HapticFeedbackConstantsCompat.CONTEXT_CLICK,
        HapticFeedbackConstantsCompat.GESTURE_END,
        HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_ACTIVATE,
        HapticFeedbackConstantsCompat.SEGMENT_TICK,
        -> OverrideVibration.TICK

        HapticFeedbackConstantsCompat.SEGMENT_FREQUENT_TICK,
        HapticFeedbackConstantsCompat.KEYBOARD_TAP,
        -> OverrideVibration.LIGHT_TICK

        HapticFeedbackConstantsCompat.VIRTUAL_KEY,
        HapticFeedbackConstantsCompat.CONFIRM,
        -> OverrideVibration.CLICK

        HapticFeedbackConstantsCompat.LONG_PRESS -> OverrideVibration.HEAVY_CLICK

        HapticFeedbackConstantsCompat.REJECT -> OverrideVibration.DOUBLE_CLICK

        HapticFeedbackConstantsCompat.TOGGLE_ON -> OverrideVibration.TOGGLE_ON

        HapticFeedbackConstantsCompat.TOGGLE_OFF -> OverrideVibration.TOGGLE_OFF

        else -> null
    }
