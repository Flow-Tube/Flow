package io.github.aedev.flow.ui.components.videoplayer.motion

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.unit.dp

/** Open and expand: M3's standard slow spatial spring, about 300 ms like YouTube's own. */
internal val playerOpenSpringSpec: FiniteAnimationSpec<Float> = MotionScheme.standard().slowSpatialSpec()

/** The expanded box gliding to a new video shape, so the page under it moves without a jump. */
internal val playerResizeSpringSpec: FiniteAnimationSpec<Float> = MotionScheme.standard().defaultSpatialSpec()

/** Collapse: critically damped and slower than the open, a glide of about 580 ms into the corner. */
internal val playerCollapseSpringSpec = spring<Float>(dampingRatio = 1f, stiffness = 130f)

// Hand-tuned on device; a MaterialTheme.motionScheme mapping changes the feel and is a design
// decision, not a refactor.
internal val miniSnapSpringSpec = spring<Float>(dampingRatio = 0.82f, stiffness = 500f)
internal val miniResizeSpringSpec = spring<Float>(dampingRatio = 0.72f, stiffness = 280f)
internal val miniDismissSpringSpec = spring<Float>(dampingRatio = 0.9f, stiffness = 340f)
internal val dragReleaseSpringSpec = spring<Float>(dampingRatio = 0.55f, stiffness = 500f)
internal val portraitFullscreenSettleSpec = spring<Float>(dampingRatio = 1f, stiffness = 360f)

/** Expansion fraction past which the body panel is fully transparent. */
internal const val BODY_CONTENT_MAX_EXPAND_FRACTION = 0.3f

/** How far the page under the video slides down over a full collapse. */
internal val BodySlideDistance = 27.dp

/**
 * Expansion fractions past which the expanded-only surfaces are composed (below) or dropped
 * (above) once the sheet is at rest. A drag released this close to an anchor has almost no
 * travel left, so the one long frame the change costs lands off the visible motion.
 */
internal const val EXPANDED_SURFACES_MOUNT_FRACTION = 0.05f
internal const val EXPANDED_SURFACES_UNMOUNT_FRACTION = 0.95f

/** Share of the widest size from which the mini player counts as wide and centres on a phone. */
internal const val WIDE_SIZE_SHARE = 0.98f

/**
 * The smallest a pinch leaves the mini player, as a share of the window's short side: the Small
 * size preset, so the controls always have the room that preset was laid out for.
 */
internal const val MINI_PLAYER_SMALLEST_SHARE = 0.35f

/** Delay before an off-target mini player is nudged back to its resting corner. */
internal const val MINI_RESNAP_DEBOUNCE_MS = 50L

/** How long the dismiss fling is allowed to travel before the player is torn down. */
internal const val MINI_DISMISS_TEARDOWN_DELAY_MS = 200L
