package io.github.aedev.flow.ui.screens.player.effects

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import io.github.aedev.flow.player.PictureInPictureHelper
import io.github.aedev.flow.player.PipBounds
import io.github.aedev.flow.ui.components.videoplayer.PlayerDraggableState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

private const val HINT_SETTLE_MS = 150L

private class CoordinatesHolder {
    var coordinates: LayoutCoordinates? = null
}

/**
 * The sheet and portrait fullscreen move the video with graphics-layer transforms, which no layout
 * pass reports, so a source hint taken on layout keeps the last laid-out spot: centred after leaving
 * PiP or portrait fullscreen, which made the next PiP entry morph from the page under it (#1671).
 * Once the motion stops, the hint is read again from where the video is actually drawn.
 */
@Composable
internal fun rememberPipSourceHintModifier(sheetState: PlayerDraggableState): Modifier {
    val activity = LocalActivity.current
    val holder = remember { CoordinatesHolder() }
    LaunchedEffect(sheetState, activity) {
        snapshotFlow { Triple(sheetState.isSettled, sheetState.portraitFsFraction, sheetState.currentValue) }
            .collectLatest { (settled) ->
                if (!settled) return@collectLatest
                delay(HINT_SETTLE_MS)
                val bounds = holder.coordinates?.takeIf { it.isAttached }?.boundsInWindow() ?: return@collectLatest
                if (bounds.isEmpty) return@collectLatest
                PictureInPictureHelper.setVideoViewBounds(
                    activity,
                    PipBounds(
                        bounds.left.roundToInt(),
                        bounds.top.roundToInt(),
                        bounds.right.roundToInt(),
                        bounds.bottom.roundToInt(),
                    ),
                )
            }
    }
    return remember(holder) { Modifier.onGloballyPositioned { holder.coordinates = it } }
}
