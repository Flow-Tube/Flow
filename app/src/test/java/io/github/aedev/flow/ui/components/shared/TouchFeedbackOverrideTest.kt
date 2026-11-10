package io.github.aedev.flow.ui.components.shared

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.core.view.HapticFeedbackConstantsCompat
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** With system touch feedback off, every haptic Flow uses must still have an effect to play (#1915). */
class TouchFeedbackOverrideTest {
    private val usedTypes =
        listOf(
            HapticFeedbackType.Confirm,
            HapticFeedbackType.ContextClick,
            HapticFeedbackType.GestureEnd,
            HapticFeedbackType.GestureThresholdActivate,
            HapticFeedbackType.LongPress,
            HapticFeedbackType.Reject,
            HapticFeedbackType.SegmentFrequentTick,
            HapticFeedbackType.SegmentTick,
            HapticFeedbackType.ToggleOff,
            HapticFeedbackType.ToggleOn,
            HapticFeedbackType.VirtualKey,
        )

    @Test
    fun everyHapticFlowUsesHasAnOverrideEffect() {
        usedTypes.forEach { type ->
            val constant = hapticConstantFor(type)
            assertWithMessage("constant for $type").that(constant).isNotNull()
            assertWithMessage("effect for $type").that(overrideVibrationFor(constant!!)).isNotNull()
        }
    }

    @Test
    fun effectsFollowTheAndroid15Table() {
        assertThat(overrideVibrationFor(HapticFeedbackConstantsCompat.LONG_PRESS)).isEqualTo(OverrideVibration.HEAVY_CLICK)
        assertThat(overrideVibrationFor(HapticFeedbackConstantsCompat.REJECT)).isEqualTo(OverrideVibration.DOUBLE_CLICK)
        assertThat(overrideVibrationFor(HapticFeedbackConstantsCompat.CONFIRM)).isEqualTo(OverrideVibration.CLICK)
        assertThat(overrideVibrationFor(HapticFeedbackConstantsCompat.SEGMENT_TICK)).isEqualTo(OverrideVibration.TICK)
        assertThat(overrideVibrationFor(HapticFeedbackConstantsCompat.TOGGLE_OFF)).isEqualTo(OverrideVibration.TOGGLE_OFF)
    }

    @Test
    fun textHandleMoveIsLeftToThePlatform() {
        assertThat(hapticConstantFor(HapticFeedbackType.TextHandleMove)).isNull()
    }
}
