package io.github.aedev.flow.ui.tv.input

import android.view.KeyEvent
import io.github.aedev.flow.ui.components.shared.MediaButton

/** Playback intents produced by remote keys on the TV player. */
enum class TvPlayerAction {
    TOGGLE_PLAYBACK,
    PLAY,
    PAUSE,
    SEEK_BACK,
    SEEK_FORWARD,
    NEXT,
    PREVIOUS,
    TOGGLE_CAPTIONS,
    SHOW_TRANSPORT,
    SHOW_UP_NEXT,
    SCRUB_BACK,
    SCRUB_FORWARD,
    COMMIT_SCRUB,
}

/**
 * Pure keyCode → intent tables. Repeat counting, acceleration, and focus routing
 * live in the player's state holders — this object stays a lookup.
 * D-pad navigation keys are never consumed by [map] so Compose focus keeps working.
 */
object TvPlayerKeyMapper {
    fun map(keyCode: Int): TvPlayerAction? =
        MediaButton.forKeyCode(keyCode)?.toTvAction()
            ?: when (keyCode) {
                KeyEvent.KEYCODE_CAPTIONS -> TvPlayerAction.TOGGLE_CAPTIONS
                KeyEvent.KEYCODE_SPACE -> TvPlayerAction.TOGGLE_PLAYBACK
                else -> null
            }

    private fun MediaButton.toTvAction(): TvPlayerAction =
        when (this) {
            MediaButton.TOGGLE -> TvPlayerAction.TOGGLE_PLAYBACK
            MediaButton.PLAY -> TvPlayerAction.PLAY
            MediaButton.PAUSE -> TvPlayerAction.PAUSE
            MediaButton.SEEK_BACK -> TvPlayerAction.SEEK_BACK
            MediaButton.SEEK_FORWARD -> TvPlayerAction.SEEK_FORWARD
            MediaButton.NEXT -> TvPlayerAction.NEXT
            MediaButton.PREVIOUS -> TvPlayerAction.PREVIOUS
        }

    /**
     * D-pad handling while the controls overlay is hidden. Center follows the Android TV
     * convention and toggles playback while revealing the transport, left/right start a scrub
     * with the seek bar focused, and up reveals with the up-next rail focused.
     */
    fun mapDpadWhenControlsHidden(keyCode: Int): TvPlayerAction? =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> TvPlayerAction.TOGGLE_PLAYBACK

            KeyEvent.KEYCODE_DPAD_LEFT -> TvPlayerAction.SCRUB_BACK

            KeyEvent.KEYCODE_DPAD_RIGHT -> TvPlayerAction.SCRUB_FORWARD

            KeyEvent.KEYCODE_DPAD_UP -> TvPlayerAction.SHOW_UP_NEXT

            KeyEvent.KEYCODE_DPAD_DOWN -> TvPlayerAction.SHOW_TRANSPORT

            else -> null
        }

    /**
     * D-pad handling while the seek bar itself is focused. Up/down stay unmapped
     * so Compose focus moves between the seek bar, transport row, and rail.
     */
    fun mapDpadWhenSeekBarFocused(keyCode: Int): TvPlayerAction? =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> TvPlayerAction.SCRUB_BACK

            KeyEvent.KEYCODE_DPAD_RIGHT -> TvPlayerAction.SCRUB_FORWARD

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> TvPlayerAction.COMMIT_SCRUB

            else -> null
        }
}
