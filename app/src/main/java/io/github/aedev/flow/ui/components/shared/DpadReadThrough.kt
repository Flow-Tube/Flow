package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val ReadFocusRingWidth = 2.dp

/** A third of the screen per press keeps a line or two of the last view in sight. */
private const val READ_STEP_FRACTION = 1f / 3f

/**
 * Text that can be selected on a touch screen. A remote can't select, and the container's focus stop
 * shows no focus, so on a D-pad the text is drawn as is.
 */
@Composable
fun FlowSelectionContainer(content: @Composable () -> Unit) {
    if (LocalDpadNavigation.current) content() else SelectionContainer(content = content)
}

/**
 * Makes a long block of text one focus stop that a remote reads through: Down and Up scroll it a third
 * of the screen at a time inside whatever scrolls it, and pass on to the next item once its end is in
 * view. Without it, focus jumps from the item above the text to the one below and skips its middle.
 * A no-op unless [LocalDpadNavigation] is on.
 */
@Composable
fun Modifier.dpadReadThrough(): Modifier {
    if (!LocalDpadNavigation.current) return this
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    var size by remember { mutableStateOf(IntSize.Zero) }
    var readTop by remember { mutableFloatStateOf(0f) }
    val step = LocalWindowInfo.current.containerSize.height * READ_STEP_FRACTION
    return onSizeChanged { size = it }
        .bringIntoViewRequester(requester)
        .border(
            width = ReadFocusRingWidth,
            color = if (focused) MaterialTheme.colorScheme.onSurface else Color.Transparent,
            shape = MaterialTheme.shapes.large,
        ).onFocusChanged { if (it.isFocused) readTop = 0f }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val down =
                when (event.key) {
                    Key.DirectionDown -> true
                    Key.DirectionUp -> false
                    else -> return@onKeyEvent false
                }
            val next = DpadReadStep.next(readTop, step, size.height.toFloat(), down) ?: return@onKeyEvent false
            readTop = next
            scope.launch { requester.bringIntoView(Rect(0f, next, size.width.toFloat(), next + step)) }
            true
        }.focusable(interactionSource = interactionSource)
}

/** Where the read-through window of [step] goes next over text [height] tall, or null at that end. */
internal object DpadReadStep {
    fun next(
        top: Float,
        step: Float,
        height: Float,
        down: Boolean,
    ): Float? {
        if (step <= 0f) return null
        val lastTop = (height - step).coerceAtLeast(0f)
        val next = if (down) (top + step).coerceAtMost(lastTop) else (top - step).coerceAtLeast(0f)
        return next.takeIf { it != top }
    }
}
