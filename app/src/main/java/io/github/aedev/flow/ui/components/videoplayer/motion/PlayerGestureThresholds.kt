package io.github.aedev.flow.ui.components.videoplayer.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity

/**
 * The density the player's gesture distances and speeds were hand-tuned on. Each value is kept as
 * the pixels it had there and scaled to the current density, so the tuned feel is unchanged on
 * that screen and every other screen gets the same physical distance instead of the same pixels.
 */
private const val TUNED_DENSITY = 3f

/**
 * Every distance (px) and speed (px/s) the player's gestures compare against, for one density.
 * Velocities are the finger's screen-space speed, as the gesture handlers measure them.
 */
@Immutable
internal class PlayerGestureThresholds(
    density: Float,
) {
    private val scale = density / TUNED_DENSITY

    /** Upward travel on the expanded video that commits to fullscreen. */
    val expandDragCommit = 80f * scale

    /** How hard the swipe-up zoom resists; smaller reaches its ceiling sooner. */
    val expandDragSoftness = 60f * scale

    /** Upward speed that commits to fullscreen however short the swipe was. */
    val fullscreenSwipeVelocity = 800f * scale

    /** Downward speed that collapses the player however short the drag was. */
    val collapseFlingVelocity = 1000f * scale

    /** Upward speed at release that takes a collapse back. */
    val collapseCancelVelocity = 1000f * scale

    /** Speed along one axis that picks the mini player's corner on that axis outright. */
    val cornerFlingVelocity = 400f * scale

    /** Sideways speed that tucks the mini player out through the side it started on. */
    val tuckFlingVelocity = 2500f * scale

    /** Downward speed that closes the mini player from a bottom corner. */
    val closeFlingVelocity = 1200f * scale

    /** Downward speed that grows the portrait fullscreen pull the rest of the way. */
    val portraitFsCommitVelocity = 2500f * scale

    /** Upward speed that takes a portrait fullscreen pull back. */
    val portraitFsCancelVelocity = 1400f * scale

    /** Screen travel under which a press on the mini player is still a tap. */
    val miniTapMovement = 24f * scale

    /** Downward travel in the centre of fullscreen that commits to leaving it. */
    val exitFullscreenDrag = 80f * scale

    /** How far past the commit point the exit drag still follows the finger, resisted. */
    val exitFullscreenOvershoot = 140f * scale

    /** Upward speed that opens the more videos row however short the swipe was. */
    val moreVideosFlingVelocity = 1000f * scale

    /** How far a swipe to seek has to go before coming back to its start can cancel it. */
    val seekUndoAway = 75f * scale

    /** How close to its start a swipe to seek has to come back to offer to cancel. */
    val seekUndoBack = 60f * scale

    /** Speed toward its edge that closes the fullscreen side panel. */
    val sidePanelCloseVelocity = 900f * scale
}

@Composable
internal fun rememberPlayerGestureThresholds(): PlayerGestureThresholds {
    val density = LocalDensity.current.density
    return remember(density) { PlayerGestureThresholds(density) }
}
