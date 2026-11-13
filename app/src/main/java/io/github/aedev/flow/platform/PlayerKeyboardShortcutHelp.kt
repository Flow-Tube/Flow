package io.github.aedev.flow.platform

import android.content.Context
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import io.github.aedev.flow.R

/** The player's keys as the system lists them under Meta + /, matching [io.github.aedev.flow.ui.components.shared.MediaKeyMapper]. */
object PlayerKeyboardShortcutHelp {
    fun group(context: Context): KeyboardShortcutGroup {
        fun key(
            label: Int,
            keyCode: Int,
            modifiers: Int = 0,
        ) = KeyboardShortcutInfo(context.getString(label), keyCode, modifiers)

        return KeyboardShortcutGroup(
            context.getString(R.string.keyboard_shortcuts_player),
            listOf(
                key(R.string.keyboard_shortcut_play_pause, KeyEvent.KEYCODE_SPACE),
                key(R.string.keyboard_shortcut_seek_back, KeyEvent.KEYCODE_DPAD_LEFT),
                key(R.string.keyboard_shortcut_seek_forward, KeyEvent.KEYCODE_DPAD_RIGHT),
                key(R.string.keyboard_shortcut_volume_up, KeyEvent.KEYCODE_DPAD_UP),
                key(R.string.keyboard_shortcut_volume_down, KeyEvent.KEYCODE_DPAD_DOWN),
                key(R.string.fullscreen, KeyEvent.KEYCODE_F),
                key(R.string.keyboard_shortcut_exit_fullscreen, KeyEvent.KEYCODE_ESCAPE),
                key(R.string.captions, KeyEvent.KEYCODE_C),
                key(R.string.next, KeyEvent.KEYCODE_N, KeyEvent.META_SHIFT_ON),
                key(R.string.previous, KeyEvent.KEYCODE_P, KeyEvent.META_SHIFT_ON),
                key(R.string.keyboard_shortcut_faster, KeyEvent.KEYCODE_PERIOD, KeyEvent.META_SHIFT_ON),
                key(R.string.keyboard_shortcut_slower, KeyEvent.KEYCODE_COMMA, KeyEvent.META_SHIFT_ON),
                key(R.string.keyboard_shortcut_play_pause, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
                key(R.string.pause, KeyEvent.KEYCODE_MEDIA_STOP),
                key(R.string.keyboard_shortcut_seek_back, KeyEvent.KEYCODE_MEDIA_REWIND),
                key(R.string.keyboard_shortcut_seek_forward, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD),
                key(R.string.next, KeyEvent.KEYCODE_MEDIA_NEXT),
                key(R.string.previous, KeyEvent.KEYCODE_MEDIA_PREVIOUS),
            ),
        )
    }
}
