package io.github.aedev.flow.ui.components.shared

import android.view.KeyEvent

/** The buttons on a media keyboard, a headset or a remote, as the one table every player reads. */
enum class MediaButton {
    TOGGLE,
    PLAY,
    PAUSE,
    SEEK_BACK,
    SEEK_FORWARD,
    NEXT,
    PREVIOUS,
    ;

    companion object {
        /** Stop pauses: a stopped player would lose the video, and the button should not. */
        fun forKeyCode(keyCode: Int): MediaButton? =
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> TOGGLE
                KeyEvent.KEYCODE_MEDIA_PLAY -> PLAY
                KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> PAUSE
                KeyEvent.KEYCODE_MEDIA_REWIND -> SEEK_BACK
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> SEEK_FORWARD
                KeyEvent.KEYCODE_MEDIA_NEXT -> NEXT
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PREVIOUS
                else -> null
            }
    }
}
