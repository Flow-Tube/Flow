package io.github.aedev.flow.ui.components.shared

import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HardwareKeyboardTest {
    private fun physical(
        deviceId: Int = 7,
        fromSoftKeyboard: Boolean = false,
        virtualDevice: Boolean = false,
        alphabeticDevice: Boolean = true,
        keyCode: Int = KeyEvent.KEYCODE_K,
    ) = isPhysicalKeyboardKey(deviceId, fromSoftKeyboard, virtualDevice, alphabeticDevice, keyCode)

    @Test
    fun `a full keyboard counts for any key`() {
        assertThat(physical()).isTrue()
        assertThat(physical(keyCode = KeyEvent.KEYCODE_DPAD_LEFT)).isTrue()
    }

    @Test
    fun `on-screen keyboards never count`() {
        assertThat(physical(deviceId = KeyCharacterMap.VIRTUAL_KEYBOARD)).isFalse()
        assertThat(physical(fromSoftKeyboard = true)).isFalse()
        assertThat(physical(virtualDevice = true)).isFalse()
    }

    @Test
    fun `a device the system calls non-alphabetic counts once it types a letter`() {
        assertThat(physical(alphabeticDevice = false, keyCode = KeyEvent.KEYCODE_A)).isTrue()
        assertThat(physical(alphabeticDevice = false, keyCode = KeyEvent.KEYCODE_SPACE)).isTrue()
        assertThat(physical(alphabeticDevice = false, keyCode = KeyEvent.KEYCODE_DPAD_CENTER)).isFalse()
        assertThat(physical(alphabeticDevice = false, keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)).isFalse()
    }

    @Test
    fun `the newest shortcut target takes focus and leaves when removed`() {
        val state = HardwareKeyboardState()
        val calls = mutableListOf<String>()
        assertThat(state.focusShortcutTarget()).isFalse()
        val removeVideo =
            state.addShortcutTarget {
                calls += "video"
                true
            }
        val removeMusic =
            state.addShortcutTarget {
                calls += "music"
                true
            }
        assertThat(state.focusShortcutTarget()).isTrue()
        removeMusic()
        assertThat(state.focusShortcutTarget()).isTrue()
        removeVideo()
        assertThat(state.focusShortcutTarget()).isFalse()
        assertThat(calls).containsExactly("music", "video").inOrder()
    }
}
