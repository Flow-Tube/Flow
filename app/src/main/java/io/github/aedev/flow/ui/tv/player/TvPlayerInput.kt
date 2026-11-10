package io.github.aedev.flow.ui.tv.player

import android.view.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import io.github.aedev.flow.ui.tv.input.TvPlayerAction
import io.github.aedev.flow.ui.tv.input.TvPlayerKeyMapper
import io.github.aedev.flow.ui.tv.player.state.TvOverlayMode

/**
 * Routes the remote's keys for the TV player: media keys always, the D-pad to playback while the
 * controls are hidden, and left and right to the scrub while the seek bar has focus. Anything it
 * leaves alone moves Compose focus as usual.
 */
internal class TvPlayerInput(
    private val overlayMode: () -> TvOverlayMode,
    private val seekBarFocused: () -> Boolean,
    private val isScrubbing: () -> Boolean,
    private val perform: (TvPlayerAction, repeatCount: Int) -> Unit,
    private val onInteraction: () -> Unit,
    private val onDownFromControls: () -> Boolean,
) {
    fun onKey(
        type: KeyEventType,
        keyCode: Int,
        repeatCount: Int,
    ): Boolean =
        when (type) {
            KeyEventType.KeyUp -> onKeyUp(keyCode)
            KeyEventType.KeyDown -> onKeyDown(keyCode, repeatCount)
            else -> false
        }

    private fun onKeyUp(keyCode: Int): Boolean {
        val endsScrub = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (!isScrubbing() || !endsScrub) return false
        perform(TvPlayerAction.COMMIT_SCRUB, 0)
        return true
    }

    private fun onKeyDown(
        keyCode: Int,
        repeatCount: Int,
    ): Boolean {
        TvPlayerKeyMapper.map(keyCode)?.let {
            perform(it, repeatCount)
            return true
        }
        val mode = overlayMode()
        val action =
            when {
                mode == TvOverlayMode.PANEL -> null
                mode == TvOverlayMode.HIDDEN || mode == TvOverlayMode.SEEK -> TvPlayerKeyMapper.mapDpadWhenControlsHidden(keyCode)
                seekBarFocused() -> TvPlayerKeyMapper.mapDpadWhenSeekBarFocused(keyCode)
                else -> null
            }
        if (action == null) {
            onInteraction()
            val opensUpNext = keyCode == KeyEvent.KEYCODE_DPAD_DOWN && overlayMode() == TvOverlayMode.TRANSPORT && !seekBarFocused()
            return opensUpNext && onDownFromControls()
        }
        perform(action, repeatCount)
        return true
    }
}
