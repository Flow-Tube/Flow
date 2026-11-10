package io.github.aedev.flow.ui.components.shared

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration

/** What a player does for a key on a physical keyboard. */
sealed interface MediaKeyAction {
    data object TogglePlayback : MediaKeyAction

    data object SeekBack : MediaKeyAction

    data object SeekForward : MediaKeyAction

    data object VolumeUp : MediaKeyAction

    data object VolumeDown : MediaKeyAction

    data object ToggleFullscreen : MediaKeyAction

    data object ExitFullscreen : MediaKeyAction

    data object ToggleCaptions : MediaKeyAction

    data object Next : MediaKeyAction

    data object Previous : MediaKeyAction

    data object SpeedUp : MediaKeyAction

    data object SpeedDown : MediaKeyAction

    /** Jump to [tenths] × 10 % of the video. */
    data class JumpTo(
        val tenths: Int,
    ) : MediaKeyAction

    /** Held keys repeat only the actions that step a value; a held Space must not flicker play and pause. */
    val repeats: Boolean
        get() = this is SeekBack || this is SeekForward || this is VolumeUp || this is VolumeDown || this is SpeedUp || this is SpeedDown
}

/**
 * The keys Flow Desktop and YouTube use. Keys held with Ctrl, Alt or Meta stay with the system, and
 * the media keys are left to the media session. With [arrowsNavigate] the arrows are left to focus,
 * as on a TV, where they are the remote's D-pad.
 */
object MediaKeyMapper {
    private val ARROW_KEYS =
        setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)

    fun map(
        keyCode: Int,
        shift: Boolean,
        otherModifier: Boolean,
        arrowsNavigate: Boolean = false,
    ): MediaKeyAction? {
        if (otherModifier) return null
        if (arrowsNavigate && keyCode in ARROW_KEYS) return null
        if (shift) {
            return when (keyCode) {
                KeyEvent.KEYCODE_N -> MediaKeyAction.Next
                KeyEvent.KEYCODE_P -> MediaKeyAction.Previous
                KeyEvent.KEYCODE_PERIOD -> MediaKeyAction.SpeedUp
                KeyEvent.KEYCODE_COMMA -> MediaKeyAction.SpeedDown
                else -> null
            }
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_K -> MediaKeyAction.TogglePlayback
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_J -> MediaKeyAction.SeekBack
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_L -> MediaKeyAction.SeekForward
            KeyEvent.KEYCODE_DPAD_UP -> MediaKeyAction.VolumeUp
            KeyEvent.KEYCODE_DPAD_DOWN -> MediaKeyAction.VolumeDown
            KeyEvent.KEYCODE_F -> MediaKeyAction.ToggleFullscreen
            KeyEvent.KEYCODE_ESCAPE -> MediaKeyAction.ExitFullscreen
            KeyEvent.KEYCODE_C -> MediaKeyAction.ToggleCaptions
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> MediaKeyAction.JumpTo(keyCode - KeyEvent.KEYCODE_0)
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> MediaKeyAction.JumpTo(keyCode - KeyEvent.KEYCODE_NUMPAD_0)
            else -> null
        }
    }
}

/** The next preset in [options] above or below [current], or null at either end. */
fun stepPlaybackSpeed(
    options: List<Float>,
    current: Float,
    faster: Boolean,
): Float? = if (faster) options.firstOrNull { it > current + SPEED_EPSILON } else options.lastOrNull { it < current - SPEED_EPSILON }

private const val SPEED_EPSILON = 0.001f

/**
 * Runs [onAction] for the keys of a physical keyboard while [enabled] and a hardware keyboard is
 * attached. Compose sends a key only into the focused subtree and nothing in a player holds focus on
 * a touch screen, so this takes focus when it turns on, as a bare focus target that TalkBack skips.
 * Keys reach it after the focused child, so a focused button or text field keeps its own, and an
 * action [onAction] reports as not applied falls through.
 */
@Composable
fun Modifier.mediaKeyboardShortcuts(
    enabled: Boolean,
    onAction: (MediaKeyAction) -> Boolean,
): Modifier {
    val configuration = LocalConfiguration.current
    val active =
        enabled &&
            configuration.keyboard == Configuration.KEYBOARD_QWERTY &&
            configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    // A remote already moves focus between the player's buttons, so the keys only bubble up to here.
    val dpad = LocalDpadNavigation.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(active, dpad) {
        if (active && !dpad) focusRequester.requestFocus()
    }
    if (!active) return this
    val target = if (dpad) this else focusRequester(focusRequester).focusTarget()
    return target
        .onKeyEvent { event ->
            val action =
                MediaKeyMapper.map(
                    keyCode = event.key.nativeKeyCode,
                    shift = event.isShiftPressed,
                    otherModifier = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed,
                    arrowsNavigate = dpad,
                ) ?: return@onKeyEvent false
            when (event.type) {
                KeyEventType.KeyDown -> {
                    if (event.nativeKeyEvent.repeatCount > 0 && !action.repeats) true else onAction(action)
                }

                else -> {
                    false
                }
            }
        }
}
