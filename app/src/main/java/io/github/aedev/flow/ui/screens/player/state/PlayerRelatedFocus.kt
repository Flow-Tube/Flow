package io.github.aedev.flow.ui.screens.player.state

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import io.github.aedev.flow.ui.components.shared.LocalHardwareKeyboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Lets a keyboard reach the related videos: Tab on the player scrolls the list that holds them to
 * the first card and focuses it. Only the list on screen binds itself, and only while it has cards.
 */
@Stable
internal class PlayerRelatedFocus {
    val firstCard = FocusRequester()
    private var reveal: (suspend () -> Unit)? = null

    fun focusFirstCard(scope: CoroutineScope): Boolean {
        val reveal = reveal ?: return false
        scope.launch {
            reveal()
            // The card is composed by the scroll's next layout pass.
            withFrameNanos {}
            firstCard.requestFocus(FocusDirection.Enter)
        }
        return true
    }

    fun bind(reveal: suspend () -> Unit): () -> Unit {
        this.reveal = reveal
        return { if (this.reveal === reveal) this.reveal = null }
    }
}

@Composable
internal fun BindRelatedFocus(
    focus: PlayerRelatedFocus,
    listState: LazyListState,
    firstIndex: Int,
    enabled: Boolean,
) {
    DisposableEffect(focus, listState, firstIndex, enabled) {
        val unbind = if (enabled) focus.bind { listState.scrollToItem(firstIndex) } else null
        onDispose { unbind?.invoke() }
    }
}

/** Esc or Shift+Tab anywhere on the page gives focus back to the player's keyboard shortcuts. */
@Composable
internal fun Modifier.keyboardReturnToPlayer(): Modifier {
    val keyboard = LocalHardwareKeyboard.current
    return onKeyEvent { event ->
        val back = event.key == Key.Escape || (event.key == Key.Tab && event.isShiftPressed)
        event.type == KeyEventType.KeyDown && back && keyboard.focusShortcutTarget()
    }
}
