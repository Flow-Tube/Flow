package io.github.aedev.flow.ui.components.videoplayer

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.videoplayer.motion.DraggablePlayerGestureHandler
import kotlin.math.roundToInt

private val HandleWidth = 28.dp

// Under Android's 200 dp limit on how much of each edge may be kept from the back gesture.
private val HandleHeight = 108.dp

/** What sits at the screen's edge for the mini player: the tuck tab and the back gesture exclusion. */
@Composable
internal fun BoxScope.MiniPlayerEdgeLayers(
    state: PlayerDraggableState,
    miniY: () -> Float,
    miniWidth: Float,
    miniHeight: Float,
    screenWidth: Float,
    handler: DraggablePlayerGestureHandler,
) {
    MiniPlayerTuckHandle(
        state = state,
        miniY = miniY,
        miniWidth = miniWidth,
        miniHeight = miniHeight,
        screenWidth = screenWidth,
        onUntuck = handler::untuck,
        onDragStart = handler::startTabDrag,
        onDrag = handler::tabDrag,
        onDragEnd = handler::endTabDrag,
    )
    MiniPlayerGestureExclusion(state = state, miniY = miniY, miniWidth = miniWidth, miniHeight = miniHeight)
}

/**
 * The tab left at the screen edge while the mini player is tucked past it. It rides at the mini
 * player's height and slides in over the last stretch of the player's way out, both read in the
 * layout phase. A tap brings the player back; a drag pulls it out with the finger. The tab is kept
 * from the system back gesture, which would otherwise take any swipe that starts on it.
 */
@Composable
private fun BoxScope.MiniPlayerTuckHandle(
    state: PlayerDraggableState,
    miniY: () -> Float,
    miniWidth: Float,
    miniHeight: Float,
    screenWidth: Float,
    onUntuck: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (uptimeMillis: Long, delta: Offset) -> Unit,
    onDragEnd: (uptimeMillis: Long) -> Unit,
) {
    val side = state.tuckedSide ?: return
    val onLeft = side == MiniPlayerTuckSide.Left
    Surface(
        shape =
            if (onLeft) {
                RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
            } else {
                RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
            },
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
        modifier =
            Modifier
                .align(if (onLeft) Alignment.TopStart else Alignment.TopEnd)
                .offset {
                    val handleWidth = HandleWidth.toPx()
                    // How much of the tab's strip the player still covers; a held tab stays put.
                    val covered =
                        if (state.isDragging) {
                            0f
                        } else if (onLeft) {
                            (state.offsetX.value + miniWidth).coerceIn(0f, handleWidth)
                        } else {
                            (screenWidth - state.offsetX.value).coerceIn(0f, handleWidth)
                        }
                    IntOffset(
                        (if (onLeft) -covered else covered).roundToInt(),
                        (miniY() + (miniHeight - HandleHeight.toPx()) / 2f).roundToInt(),
                    )
                }.size(HandleWidth, HandleHeight)
                .systemGestureExclusion()
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.mini_player_show),
                    onClick = onUntuck,
                ).pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd(SystemClock.uptimeMillis()) },
                        onDragCancel = { onDragEnd(SystemClock.uptimeMillis()) },
                    ) { change, dragAmount ->
                        change.consume()
                        onDrag(change.uptimeMillis, dragAmount)
                    }
                },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (onLeft) Icons.Rounded.ChevronRight else Icons.Rounded.ChevronLeft,
                contentDescription = stringResource(R.string.mini_player_show),
            )
        }
    }
}

/**
 * Keeps the system back gesture off the mini player while it rests at the screen's edge. The
 * player is moved by a graphics layer that an exclusion on it would not follow, so an empty box
 * stands in for it, placed in the layout phase where the player is. It takes no touches.
 */
@Composable
private fun BoxScope.MiniPlayerGestureExclusion(
    state: PlayerDraggableState,
    miniY: () -> Float,
    miniWidth: Float,
    miniHeight: Float,
) {
    val shown by remember(state) {
        derivedStateOf { state.expandFraction.value > MINI_SETTLED_FRACTION && state.tuckedSide == null }
    }
    if (!shown) return
    val density = LocalDensity.current
    Box(
        modifier =
            Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(state.offsetX.value.roundToInt(), miniY().roundToInt()) }
                .size(with(density) { miniWidth.toDp() }, with(density) { miniHeight.toDp() })
                .systemGestureExclusion(),
    )
}

private const val MINI_SETTLED_FRACTION = 0.999f
