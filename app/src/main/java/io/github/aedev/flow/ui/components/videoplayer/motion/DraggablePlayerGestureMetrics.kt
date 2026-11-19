package io.github.aedev.flow.ui.components.videoplayer.motion

import androidx.compose.ui.hapticfeedback.HapticFeedback
import io.github.aedev.flow.ui.components.videoplayer.PlayerDraggableState

/**
 * The layout geometry the gesture handlers read at gesture time. Written from a `SideEffect` on
 * every composition of the layout so a pointer coroutine always sees the current bounds without
 * the composable holding one `rememberUpdatedState` per value.
 */
internal class DraggablePlayerGestureMetrics {
    var minX = 0f
    var maxX = 0f
    var minY = 0f
    var maxY = 0f
    var statusBarHeight = 0f
    var targetMiniX = 0f
    var targetMiniY = 0f
    var screenWidth = 0f
    var screenHeight = 0f
    var miniWidth = 0f
    var baseMiniWidth = 0f
    var maxWideWidth = 0f
    var expandedVideoWidth = 1f
    var clampedAspect = 16f / 9f
    var margin = 0f
    var bottomNavPad = 0f
    var stablePhoneCenteredX = 0f
    var isLargeScreen = false
    var isLandscape = false
    var isFullscreen = false
    var tapToExpand = true
    var thresholds = PlayerGestureThresholds(density = 1f)

    /** The smallest size a pinch rests at, as a multiple of the size setting. */
    var minSizeScale = 1f
    var haptics: HapticFeedback? = null
    var onFullscreenGesture: (() -> Unit)? = null
    var onCollapseGesture: (() -> Unit)? = null
    var onDismiss: () -> Unit = {}

    val miniHeight: Float get() = miniWidth / clampedAspect.coerceAtLeast(0.01f)

    val bounds: MiniPlayerBounds
        get() = MiniPlayerBounds(minX = minX, maxX = maxX, minY = minY, maxY = maxY)

    fun miniBoxWidth(envelopeSide: Float): Float = if (clampedAspect >= 1f) envelopeSide else envelopeSide * clampedAspect

    /**
     * Pointer events arrive in expanded-local space because the morph is a graphicsLayer scale on
     * the same node; every delta, slop distance and velocity is multiplied by this to get screen
     * travel. It is read at event time, never cached, because the scale changes mid-collapse.
     */
    fun liveGestureScale(state: PlayerDraggableState): Float =
        lerpClamped(
            1f,
            miniBoxWidth(baseMiniWidth * state.miniSizeScale.value)
                .coerceAtMost(maxWideWidth) / expandedVideoWidth.coerceAtLeast(1f),
            state.expandFraction.value,
        )
}
