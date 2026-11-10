package io.github.aedev.flow.ui.components.shared

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaKeyMapperTest {
    private fun map(
        keyCode: Int,
        shift: Boolean = false,
        otherModifier: Boolean = false,
    ) = MediaKeyMapper.map(keyCode, shift, otherModifier)

    @Test
    fun `desktop keys map to player actions`() {
        assertThat(map(KeyEvent.KEYCODE_SPACE)).isEqualTo(MediaKeyAction.TogglePlayback)
        assertThat(map(KeyEvent.KEYCODE_K)).isEqualTo(MediaKeyAction.TogglePlayback)
        assertThat(map(KeyEvent.KEYCODE_J)).isEqualTo(MediaKeyAction.SeekBack)
        assertThat(map(KeyEvent.KEYCODE_DPAD_LEFT)).isEqualTo(MediaKeyAction.SeekBack)
        assertThat(map(KeyEvent.KEYCODE_L)).isEqualTo(MediaKeyAction.SeekForward)
        assertThat(map(KeyEvent.KEYCODE_DPAD_RIGHT)).isEqualTo(MediaKeyAction.SeekForward)
        assertThat(map(KeyEvent.KEYCODE_DPAD_UP)).isEqualTo(MediaKeyAction.VolumeUp)
        assertThat(map(KeyEvent.KEYCODE_DPAD_DOWN)).isEqualTo(MediaKeyAction.VolumeDown)
        assertThat(map(KeyEvent.KEYCODE_F)).isEqualTo(MediaKeyAction.ToggleFullscreen)
        assertThat(map(KeyEvent.KEYCODE_ESCAPE)).isEqualTo(MediaKeyAction.ExitFullscreen)
        assertThat(map(KeyEvent.KEYCODE_C)).isEqualTo(MediaKeyAction.ToggleCaptions)
    }

    @Test
    fun `arrows are left to focus when they are a remote's d-pad`() {
        listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
            .forEach { assertThat(MediaKeyMapper.map(it, shift = false, otherModifier = false, arrowsNavigate = true)).isNull() }
        assertThat(MediaKeyMapper.map(KeyEvent.KEYCODE_SPACE, shift = false, otherModifier = false, arrowsNavigate = true))
            .isEqualTo(MediaKeyAction.TogglePlayback)
        assertThat(MediaKeyMapper.map(KeyEvent.KEYCODE_L, shift = false, otherModifier = false, arrowsNavigate = true))
            .isEqualTo(MediaKeyAction.SeekForward)
    }

    @Test
    fun `shifted keys step the queue and the speed`() {
        assertThat(map(KeyEvent.KEYCODE_N, shift = true)).isEqualTo(MediaKeyAction.Next)
        assertThat(map(KeyEvent.KEYCODE_P, shift = true)).isEqualTo(MediaKeyAction.Previous)
        assertThat(map(KeyEvent.KEYCODE_PERIOD, shift = true)).isEqualTo(MediaKeyAction.SpeedUp)
        assertThat(map(KeyEvent.KEYCODE_COMMA, shift = true)).isEqualTo(MediaKeyAction.SpeedDown)
        assertThat(map(KeyEvent.KEYCODE_N)).isNull()
        assertThat(map(KeyEvent.KEYCODE_SPACE, shift = true)).isNull()
    }

    @Test
    fun `digits jump to tenths on both rows`() {
        assertThat(map(KeyEvent.KEYCODE_0)).isEqualTo(MediaKeyAction.JumpTo(0))
        assertThat(map(KeyEvent.KEYCODE_7)).isEqualTo(MediaKeyAction.JumpTo(7))
        assertThat(map(KeyEvent.KEYCODE_NUMPAD_9)).isEqualTo(MediaKeyAction.JumpTo(9))
    }

    @Test
    fun `system and media keys are left alone`() {
        assertThat(map(KeyEvent.KEYCODE_SPACE, otherModifier = true)).isNull()
        assertThat(map(KeyEvent.KEYCODE_C, otherModifier = true)).isNull()
        assertThat(map(KeyEvent.KEYCODE_TAB)).isNull()
        assertThat(map(KeyEvent.KEYCODE_ENTER)).isNull()
        assertThat(map(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)).isNull()
        assertThat(map(KeyEvent.KEYCODE_M)).isNull()
    }

    @Test
    fun `only stepping actions repeat while held`() {
        assertThat(MediaKeyAction.SeekForward.repeats).isTrue()
        assertThat(MediaKeyAction.VolumeDown.repeats).isTrue()
        assertThat(MediaKeyAction.SpeedUp.repeats).isTrue()
        assertThat(MediaKeyAction.TogglePlayback.repeats).isFalse()
        assertThat(MediaKeyAction.ToggleFullscreen.repeats).isFalse()
        assertThat(MediaKeyAction.JumpTo(3).repeats).isFalse()
    }

    @Test
    fun `speed steps to the neighbouring preset and stops at the ends`() {
        val options = listOf(0.5f, 1f, 1.5f, 2f)
        assertThat(stepPlaybackSpeed(options, 1f, faster = true)).isEqualTo(1.5f)
        assertThat(stepPlaybackSpeed(options, 1f, faster = false)).isEqualTo(0.5f)
        assertThat(stepPlaybackSpeed(options, 1.2f, faster = true)).isEqualTo(1.5f)
        assertThat(stepPlaybackSpeed(options, 1.2f, faster = false)).isEqualTo(1f)
        assertThat(stepPlaybackSpeed(options, 2f, faster = true)).isNull()
        assertThat(stepPlaybackSpeed(options, 0.5f, faster = false)).isNull()
    }
}
