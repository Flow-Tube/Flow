package io.github.aedev.flow.ui.components.videoplayer

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.aedev.flow.ui.components.videoplayer.motion.MINI_RESNAP_DEBOUNCE_MS
import io.github.aedev.flow.ui.components.videoplayer.motion.MiniPlayerResnapTargets
import io.github.aedev.flow.ui.components.videoplayer.motion.playerResizeSpringSpec
import io.github.aedev.flow.ui.components.videoplayer.motion.resnapMiniPlayer
import kotlinx.coroutines.delay

/**
 * Nudges a settled mini player back onto its resting corner whenever the bounds change under it
 * (nav bar shown or hidden, rotation, wide mode). Keyed on settled values only, never on a live
 * fraction, so it cannot restart per frame.
 */
@Composable
internal fun MiniPlayerResnapEffect(
    state: PlayerDraggableState,
    isCollapsedTarget: Boolean,
    targets: MiniPlayerResnapTargets,
) {
    LaunchedEffect(
        isCollapsedTarget,
        targets.targetMiniX,
        targets.targetMiniY,
        targets.isWideMode,
        targets.isLargeScreen,
    ) {
        if (state.expandFraction.targetValue <= 0.5f || state.isDragging || state.tuckedSide != null) return@LaunchedEffect
        delay(MINI_RESNAP_DEBOUNCE_MS)
        if (state.isDragging) return@LaunchedEffect
        resnapMiniPlayer(state, targets)
    }
}

/**
 * Hands the host a live reading of the expanded player's bottom edge, in px. A value published from
 * here went stale whenever the video's height glided after the open (#2373), because nothing read it
 * in composition; the host now reads the provider itself, only while something is anchored to it.
 */
@Composable
internal fun ReportExpandedPlayerBottom(
    statusBarHeight: Float,
    videoHeightProvider: () -> Float,
    onChanged: (() -> Float) -> Unit,
) {
    val bottom = remember(statusBarHeight, videoHeightProvider) { { statusBarHeight + videoHeightProvider() } }
    val currentOnChanged by rememberUpdatedState(onChanged)
    SideEffect { currentOnChanged(bottom) }
}

/**
 * The expanded video's [height], gliding when the video's shape changes. The shape usually arrives
 * with the streams just after an open has landed, and a jump drags the whole page under the video
 * with it. A new [width] (rotation, a resized window) or a sheet resting mini takes the new height
 * at once, since nothing on screen is anchored to the old one.
 */
@Composable
internal fun rememberGlidingVideoHeight(
    state: PlayerDraggableState,
    width: Float,
    height: Float,
): () -> Float {
    val glide = remember { GlidingHeight(width, height) }
    LaunchedEffect(width, height) {
        if (width != glide.width || state.expandFraction.value > 0.999f) {
            glide.width = width
            glide.height.snapTo(height)
        } else {
            glide.height.animateTo(height, playerResizeSpringSpec)
        }
    }
    return remember(state, glide, width, height) {
        {
            if (width != glide.width || state.expandFraction.value > 0.999f) height else glide.height.value
        }
    }
}

private class GlidingHeight(
    var width: Float,
    height: Float,
) {
    val height = Animatable(height)
}

/**
 * Whether the player shows its immersive fullscreen layout. A fullscreen that does not turn the
 * window first slides the video to the centre, as the list pull does, and swaps to the immersive
 * layout once it is there; leaving slides it back to the page. Composition reads only whether the
 * slide has reached the centre, never the slide itself.
 */
@Composable
internal fun rememberImmersiveFullscreen(
    state: PlayerDraggableState,
    isFullscreen: Boolean,
    isLandscape: Boolean,
    isLargeWindow: Boolean,
): Boolean {
    LaunchedEffect(isFullscreen) {
        if (!isFullscreen) state.settlePortraitFullscreen()
    }
    val atCentre by remember(state) { derivedStateOf { state.portraitFsFraction >= 0.999f } }
    val isExpanded = state.currentValue == PlayerSheetValue.Expanded
    val slidingIn = isFullscreen && !isLandscape && !isLargeWindow && isExpanded && !atCentre
    LaunchedEffect(slidingIn) {
        if (slidingIn) state.animatePortraitFullscreen(1f, 0f)
    }
    return !slidingIn &&
        isImmersivePlayer(
            isExpanded = isExpanded,
            isFullscreen = isFullscreen,
            isLandscape = isLandscape,
            isLargeWindow = isLargeWindow,
        )
}
