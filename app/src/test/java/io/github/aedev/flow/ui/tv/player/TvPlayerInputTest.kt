package io.github.aedev.flow.ui.tv.player

import android.view.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.ui.tv.input.TvPlayerAction
import io.github.aedev.flow.ui.tv.player.state.TvOverlayMode
import org.junit.Test

class TvPlayerInputTest {
    private val performed = mutableListOf<TvPlayerAction>()

    private fun input(mode: TvOverlayMode) =
        TvPlayerInput(
            overlayMode = { mode },
            seekBarFocused = { false },
            isScrubbing = { false },
            perform = { action, _ -> performed += action },
            onInteraction = {},
            onDownFromControls = { false },
        )

    @Test
    fun `a load error takes the keys like a panel`() {
        assertThat(tvPlayerInputMode(TvOverlayMode.HIDDEN, countdownActive = false, errorShown = true))
            .isEqualTo(TvOverlayMode.PANEL)
        assertThat(tvPlayerInputMode(TvOverlayMode.TRANSPORT, countdownActive = false, errorShown = true))
            .isEqualTo(TvOverlayMode.PANEL)
    }

    @Test
    fun `the countdown takes the keys like a panel`() {
        assertThat(tvPlayerInputMode(TvOverlayMode.HIDDEN, countdownActive = true, errorShown = false))
            .isEqualTo(TvOverlayMode.PANEL)
    }

    @Test
    fun `without an error or countdown the overlay mode drives the keys`() {
        TvOverlayMode.entries.forEach { mode ->
            assertThat(tvPlayerInputMode(mode, countdownActive = false, errorShown = false)).isEqualTo(mode)
        }
    }

    @Test
    fun `in panel mode OK and the arrows reach the focused button`() {
        val input = input(TvOverlayMode.PANEL)
        listOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
        ).forEach { key ->
            assertThat(input.onKey(KeyEventType.KeyDown, key, repeatCount = 0)).isFalse()
        }
        assertThat(performed).isEmpty()
    }

    @Test
    fun `with the controls hidden OK toggles playback`() {
        val handled = input(TvOverlayMode.HIDDEN).onKey(KeyEventType.KeyDown, KeyEvent.KEYCODE_DPAD_CENTER, repeatCount = 0)
        assertThat(handled).isTrue()
        assertThat(performed).containsExactly(TvPlayerAction.TOGGLE_PLAYBACK)
    }

    @Test
    fun `media keys still work in panel mode`() {
        val handled = input(TvOverlayMode.PANEL).onKey(KeyEventType.KeyDown, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, repeatCount = 0)
        assertThat(handled).isTrue()
        assertThat(performed).containsExactly(TvPlayerAction.TOGGLE_PLAYBACK)
    }
}
