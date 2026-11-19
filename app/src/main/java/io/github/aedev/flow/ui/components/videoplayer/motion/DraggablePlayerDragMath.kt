package io.github.aedev.flow.ui.components.videoplayer.motion

import io.github.aedev.flow.ui.components.videoplayer.MiniPlayerCorner
import io.github.aedev.flow.ui.components.videoplayer.MiniPlayerTuckSide
import kotlin.math.abs

/** Ceiling the zoom approaches but never reaches, so the drag always has somewhere to go. */
private const val EXPAND_DRAG_MAX_ZOOM = 1.06f

private const val COLLAPSE_COMMIT_FRACTION = 0.2f
private const val CORNER_FLING_AXIS_DOMINANCE = 0.8f
private const val CORNER_SWITCH_TRAVEL_FRACTION = 0.15f
private const val CORNER_VELOCITY_PROJECTION_S = 0.3f
private const val TUCK_TRAVEL_FRACTION = 0.5f
private const val TUCK_AXIS_DOMINANCE = 3f

/** Share of the finger's travel past a bound that the mini player still follows. */
private const val RUBBER_BAND_FOLLOW = 0.35f

/** How far below its lowest resting place, in mini player heights, a release closes it. */
private const val CLOSE_BELOW_HEIGHTS = 0.4f
private const val CLOSE_AXIS_DOMINANCE = 2f

/** Share of the pull that commits to portrait fullscreen. */
private const val PORTRAIT_FS_COMMIT_FRACTION = 0.25f

internal fun lerpClamped(
    start: Float,
    stop: Float,
    fraction: Float,
): Float = start + (stop - start) * fraction.coerceIn(0f, 1f)

internal fun expandDragZoomFor(
    travelPx: Float,
    thresholds: PlayerGestureThresholds,
): Float {
    if (travelPx <= 0f) return 1f
    val progress = travelPx / (travelPx + thresholds.expandDragSoftness)
    return 1f + (EXPAND_DRAG_MAX_ZOOM - 1f) * progress
}

internal fun shouldEnterFullscreenFromSwipe(
    totalUpwardDragPx: Float,
    scaledVelocityY: Float,
    thresholds: PlayerGestureThresholds,
): Boolean = totalUpwardDragPx > thresholds.expandDragCommit || scaledVelocityY < -thresholds.fullscreenSwipeVelocity

/**
 * Whether a released portrait-fullscreen pull grows the rest of the way. A flick back up is the
 * user taking the pull back, so it wins over how far they had already travelled before they
 * changed their mind.
 */
internal fun shouldEnterPortraitFullscreen(
    fraction: Float,
    velocityY: Float,
    thresholds: PlayerGestureThresholds,
): Boolean =
    velocityY > -thresholds.portraitFsCancelVelocity &&
        (fraction > PORTRAIT_FS_COMMIT_FRACTION || velocityY > thresholds.portraitFsCommitVelocity)

/**
 * The release fling, handed over in pixels a second, as the fraction a second the settle
 * animation runs in. The pixel value passed straight through starts the spring hundreds of times
 * too fast, which throws the page below off screen and back before it settles.
 */
internal fun fractionVelocity(
    velocityY: Float,
    travelPx: Float,
): Float = velocityY / travelPx.coerceAtLeast(1f)

/** Where the mini player sits for a finger at [raw]: on it inside the bounds, 35% of the way past them. */
internal fun rubberBand(
    raw: Float,
    min: Float,
    max: Float,
): Float =
    when {
        raw < min -> min - (min - raw) * RUBBER_BAND_FOLLOW
        raw > max -> max + (raw - max) * RUBBER_BAND_FOLLOW
        else -> raw
    }

/** Whether the mini player has been taken far enough below its lowest resting place to close. */
internal fun isPastMiniCloseLine(
    fingerY: Float,
    maxY: Float,
    miniHeight: Float,
): Boolean = fingerY - maxY > miniHeight * CLOSE_BELOW_HEIGHTS

/**
 * Whether a released mini player closes by leaving through the bottom: the finger took it well past
 * its lowest resting place, or flicked it down from a bottom corner. A flick down from a top corner
 * only moves it to the bottom corner.
 */
internal fun shouldCloseMiniDownward(
    fingerY: Float,
    maxY: Float,
    miniHeight: Float,
    startedAtBottom: Boolean,
    velocityX: Float,
    velocityY: Float,
    thresholds: PlayerGestureThresholds,
): Boolean =
    isPastMiniCloseLine(fingerY, maxY, miniHeight) ||
        (
            startedAtBottom &&
                velocityY > thresholds.closeFlingVelocity &&
                velocityY > abs(velocityX) * CLOSE_AXIS_DOMINANCE
        )

/**
 * Whether a released collapse drag finishes in the mini player. A flick back up is the user
 * changing their mind, so it wins over how far the drag had already gone, as it does for the
 * portrait fullscreen pull.
 */
internal fun shouldCollapseOnRelease(
    fraction: Float,
    scaledVelocityY: Float,
    thresholds: PlayerGestureThresholds,
): Boolean =
    scaledVelocityY > -thresholds.collapseCancelVelocity &&
        (fraction > COLLAPSE_COMMIT_FRACTION || scaledVelocityY > thresholds.collapseFlingVelocity)

internal data class MiniPlayerBounds(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
)

internal val MiniPlayerCorner.isLeft: Boolean
    get() = this == MiniPlayerCorner.TopLeft || this == MiniPlayerCorner.BottomLeft

internal val MiniPlayerCorner.isTop: Boolean
    get() = this == MiniPlayerCorner.TopLeft || this == MiniPlayerCorner.TopRight

internal fun cornerFor(
    left: Boolean,
    top: Boolean,
): MiniPlayerCorner =
    when {
        left && top -> MiniPlayerCorner.TopLeft
        left -> MiniPlayerCorner.BottomLeft
        top -> MiniPlayerCorner.TopRight
        else -> MiniPlayerCorner.BottomRight
    }

internal fun cornerTargetX(
    corner: MiniPlayerCorner,
    minX: Float,
    maxX: Float,
): Float = if (corner.isLeft) minX else maxX

internal fun cornerTargetY(
    corner: MiniPlayerCorner,
    minY: Float,
    maxY: Float,
): Float = if (corner.isTop) minY else maxY

/**
 * Picks the corner a released mini player settles into: a dominant fling wins outright, otherwise
 * the position projected 300 ms along the velocity has to cross 15% of the travel to switch.
 */
internal fun resolveMiniPlayerCorner(
    current: MiniPlayerCorner,
    currentX: Float,
    currentY: Float,
    bounds: MiniPlayerBounds,
    scaledVelocityX: Float,
    scaledVelocityY: Float,
    thresholds: PlayerGestureThresholds,
): MiniPlayerCorner {
    val originX = cornerTargetX(current, bounds.minX, bounds.maxX)
    val originY = cornerTargetY(current, bounds.minY, bounds.maxY)
    val totalTravelX = (bounds.maxX - bounds.minX).coerceAtLeast(1f)
    val totalTravelY = (bounds.maxY - bounds.minY).coerceAtLeast(1f)
    val switchThresholdX = totalTravelX * CORNER_SWITCH_TRAVEL_FRACTION
    val switchThresholdY = totalTravelY * CORNER_SWITCH_TRAVEL_FRACTION
    val projectedDeltaX = (currentX - originX) + scaledVelocityX * CORNER_VELOCITY_PROJECTION_S
    val projectedDeltaY = (currentY - originY) + scaledVelocityY * CORNER_VELOCITY_PROJECTION_S
    val wasLeft = current.isLeft
    val wasTop = current.isTop

    val goLeft =
        when {
            abs(scaledVelocityX) > thresholds.cornerFlingVelocity &&
                abs(scaledVelocityX) > abs(scaledVelocityY) * CORNER_FLING_AXIS_DOMINANCE -> {
                scaledVelocityX < 0
            }

            wasLeft && projectedDeltaX > switchThresholdX -> {
                false
            }

            !wasLeft && projectedDeltaX < -switchThresholdX -> {
                true
            }

            else -> {
                wasLeft
            }
        }
    val goTop =
        when {
            abs(scaledVelocityY) > thresholds.cornerFlingVelocity &&
                abs(scaledVelocityY) > abs(scaledVelocityX) * CORNER_FLING_AXIS_DOMINANCE -> {
                scaledVelocityY < 0
            }

            wasTop && projectedDeltaY > switchThresholdY -> {
                false
            }

            !wasTop && projectedDeltaY < -switchThresholdY -> {
                true
            }

            else -> {
                wasTop
            }
        }
    return cornerFor(left = goLeft, top = goTop)
}

/**
 * The edge a released mini player tucks into: the finger took it half its width past a side, or
 * flung it hard and clearly sideways out through the side it started on. A fling from the other
 * side is a throw to the opposite corner, never a tuck. Null keeps it on screen.
 */
internal fun resolveMiniPlayerTuck(
    fingerX: Float,
    startedOnLeft: Boolean,
    bounds: MiniPlayerBounds,
    miniWidth: Float,
    velocityX: Float,
    velocityY: Float,
    thresholds: PlayerGestureThresholds,
): MiniPlayerTuckSide? {
    val reach = miniWidth * TUCK_TRAVEL_FRACTION
    val sideways = abs(velocityX) > abs(velocityY) * TUCK_AXIS_DOMINANCE
    return when {
        fingerX - bounds.maxX > reach -> MiniPlayerTuckSide.Right
        bounds.minX - fingerX > reach -> MiniPlayerTuckSide.Left
        sideways && velocityX > thresholds.tuckFlingVelocity && !startedOnLeft -> MiniPlayerTuckSide.Right
        sideways && velocityX < -thresholds.tuckFlingVelocity && startedOnLeft -> MiniPlayerTuckSide.Left
        else -> null
    }
}

/** Where a tucked mini player rests: wholly past the edge, its handle the only part on screen. */
internal fun tuckedMiniX(
    side: MiniPlayerTuckSide,
    screenWidth: Float,
    miniWidth: Float,
): Float = if (side == MiniPlayerTuckSide.Right) screenWidth else -miniWidth
