package io.github.aedev.flow.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** The keys a focused item takes as a click, which a remote's OK sends. */
private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar)

/**
 * Lets OK through only from a press that starts here. Compose counts an OK key repeat as a press,
 * so without this an OK still held from the long press that opened a menu would click its first item.
 */
class HeldOkGate {
    private var pressedHere = false

    /** True when the OK event should be swallowed. */
    fun swallows(
        isKeyDown: Boolean,
        repeatCount: Int,
    ): Boolean {
        if (isKeyDown && repeatCount == 0) pressedHere = true
        return !pressedHere
    }
}

/**
 * For a menu or dialog a long press of OK can open: the key's repeats and its release select nothing
 * until OK is pressed again inside it.
 */
@Composable
fun Modifier.ignoreHeldOk(): Modifier {
    val gate = remember { HeldOkGate() }
    return onPreviewKeyEvent { event ->
        event.key in OK_KEYS &&
            gate.swallows(isKeyDown = event.type == KeyEventType.KeyDown, repeatCount = event.nativeKeyEvent.repeatCount)
    }
}
