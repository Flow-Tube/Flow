package io.github.aedev.flow.ui.components.shared

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether a physical keyboard has typed in this window. Android reports a keyboard in the
 * configuration only for some devices: a host keyboard passed through Waydroid or a desktop
 * container can type without it, so the first key such a keyboard sends counts as well.
 */
@Stable
class HardwareKeyboardState {
    var inUse by mutableStateOf(false)
        private set

    private val shortcutTargets = ArrayList<() -> Boolean>()
    private var redispatching = false

    /** Adds a player's request for its shortcut focus; the newest one wins. Returns its removal. */
    internal fun addShortcutTarget(requestFocus: () -> Boolean): () -> Unit {
        shortcutTargets += requestFocus
        return { shortcutTargets.remove(requestFocus) }
    }

    /** Hands focus to the player on screen, so its shortcuts hear the keys again. */
    fun focusShortcutTarget(): Boolean = shortcutTargets.lastOrNull()?.invoke() == true

    /**
     * A shortcut key that no focused node took, as after a click cleared focus: the player takes
     * focus back and the key is sent once more, so the press still counts.
     */
    fun onUnhandledKeyDown(
        event: KeyEvent,
        redispatch: (KeyEvent) -> Boolean,
    ): Boolean {
        if (redispatching || event.repeatCount > 0) return false
        val otherModifier = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed
        MediaKeyMapper.map(event.keyCode, event.isShiftPressed, otherModifier) ?: return false
        if (!focusShortcutTarget()) return false
        redispatching = true
        return try {
            redispatch(event)
        } finally {
            redispatching = false
        }
    }

    fun onKeyEvent(event: KeyEvent) {
        if (inUse) return
        val device = event.device
        inUse =
            isPhysicalKeyboardKey(
                deviceId = event.deviceId,
                fromSoftKeyboard = event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0,
                virtualDevice = device?.isVirtual ?: true,
                alphabeticDevice = device?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC,
                keyCode = event.keyCode,
            )
    }
}

/**
 * True for a key from a real keyboard: not an on-screen keyboard, which sends from the virtual
 * device, and either a full keyboard or a letter, which a remote or a game pad cannot send.
 */
internal fun isPhysicalKeyboardKey(
    deviceId: Int,
    fromSoftKeyboard: Boolean,
    virtualDevice: Boolean,
    alphabeticDevice: Boolean,
    keyCode: Int,
): Boolean {
    if (deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD || fromSoftKeyboard || virtualDevice) return false
    return alphabeticDevice || keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z || keyCode == KeyEvent.KEYCODE_SPACE
}

val LocalHardwareKeyboard = staticCompositionLocalOf { HardwareKeyboardState() }
