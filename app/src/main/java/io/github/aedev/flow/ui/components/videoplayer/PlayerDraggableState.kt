package io.github.aedev.flow.ui.components.videoplayer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import coil3.memory.MemoryCache
import io.github.aedev.flow.ui.components.videoplayer.motion.DraggablePlayerMotionController
import io.github.aedev.flow.ui.components.videoplayer.motion.cornerTargetX
import io.github.aedev.flow.ui.components.videoplayer.motion.cornerTargetY
import io.github.aedev.flow.ui.components.videoplayer.motion.miniResizeSpringSpec
import io.github.aedev.flow.ui.components.videoplayer.motion.playerCollapseSpringSpec
import io.github.aedev.flow.ui.components.videoplayer.motion.playerOpenSpringSpec
import io.github.aedev.flow.ui.components.videoplayer.motion.portraitFullscreenSettleSpec
import io.github.aedev.flow.ui.components.videoplayer.motion.withSteadyFrames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class PlayerSheetValue { Expanded, Collapsed }

enum class MiniPlayerCorner { TopLeft, TopRight, BottomLeft, BottomRight }

enum class MiniPlayerTuckSide { Left, Right }

/** Where an open grows from: the thumbnail that was tapped, or below the screen when none is in view. */
sealed interface SheetOpenOrigin {
    /**
     * [coversWindow]: whatever opened the player already fills the window around the thumbnail, as
     * the background bar does once grown, so the page's ground starts full instead of growing.
     */
    data class Thumbnail(
        val windowBounds: Rect,
        val cornerRadiusPx: Float,
        val imageKey: MemoryCache.Key?,
        val coversWindow: Boolean = false,
    ) : SheetOpenOrigin

    data object BelowScreen : SheetOpenOrigin
}

class PlayerDraggableState(
    val offsetX: Animatable<Float, AnimationVector1D>,
    val offsetY: Animatable<Float, AnimationVector1D>,
    val expandFraction: Animatable<Float, AnimationVector1D>,
    val scope: CoroutineScope,
) {
    var corner by mutableStateOf(MiniPlayerCorner.BottomRight)
    var isDragging by mutableStateOf(false)

    /** The edge the mini player is tucked past, with only its handle on screen; null while in view. */
    var tuckedSide by mutableStateOf<MiniPlayerTuckSide?>(null)

    /** Zoom applied while dragging up to enter fullscreen, read in the draw phase only. */
    val expandDragScale = Animatable(1f)

    var cachedTargetX by mutableFloatStateOf(0f)
    var cachedTargetY by mutableFloatStateOf(0f)

    val miniSizeScale = Animatable(1f)
    var isShrinkingToCorner by mutableStateOf(false)

    /** The video box's corner radius in its own pre-scale px, published by the layout for what it draws inside. */
    internal var morphCornerRadiusPx: () -> Float = { 0f }

    /** Drawn in place of the mini corner while an open grows out of it; cleared once the open ends. */
    var openOrigin by mutableStateOf<SheetOpenOrigin?>(null)
        private set
    private var openGeneration = 0

    /** The cached image of the card the last open grew from, drawn while the poster loads. */
    var posterPlaceholderKey by mutableStateOf<MemoryCache.Key?>(null)
        private set

    var miniVisualScale by mutableFloatStateOf(1f)

    /**
     * How far the expanded video has slid from the top of the page to the centre of the window on
     * its way into portrait fullscreen, read in the layout and draw phases only. Past 1 the video
     * sits below the centre, where a fullscreen exit drag let it go.
     */
    var portraitFsFraction by mutableFloatStateOf(0f)
        private set

    /** The distance [portraitFsFraction] spans in px, published by the layout as it measures. */
    internal var portraitFsTravel = 1f
    private var portraitFsJob: Job? = null

    /** True only while no finger is down and nothing on the sheet is still moving. */
    val isSettled: Boolean
        get() =
            !isDragging &&
                !expandFraction.isRunning &&
                !offsetX.isRunning &&
                !offsetY.isRunning &&
                !miniSizeScale.isRunning &&
                !expandDragScale.isRunning

    internal val motion =
        DraggablePlayerMotionController(
            offsetX = offsetX,
            offsetY = offsetY,
            expandFraction = expandFraction,
            miniSizeScale = miniSizeScale,
        )

    /** True while the floating mini player is in wide (enlarged) mode. */
    val isInlineMode: Boolean get() = miniSizeScale.value > 1.5f

    private val currentValueState =
        derivedStateOf {
            if (expandFraction.targetValue > 0.5f) {
                PlayerSheetValue.Collapsed
            } else {
                PlayerSheetValue.Expanded
            }
        }

    val currentValue: PlayerSheetValue get() = currentValueState.value

    val fraction: Float get() = expandFraction.value

    /**
     * [velocity] is the release speed in fraction per second; without one the sheet keeps whatever
     * speed it already has, so a tap mid-collapse turns it around without a stop.
     */
    fun expand(velocity: Float? = null) {
        if (openOrigin != null) return
        corner = MiniPlayerCorner.BottomRight
        tuckedSide = null
        scope.launch {
            isShrinkingToCorner = false
            val anim = playerOpenSpringSpec
            withSteadyFrames {
                launch { motion.resize { miniSizeScale.animateTo(1f, anim) } }
                launch {
                    motion.animateFraction {
                        expandFraction.animateTo(0f, anim, initialVelocity = velocity ?: expandFraction.velocity)
                    }
                }
                launch {
                    motion.moveOffsets {
                        launch { offsetX.animateTo(0f, anim) }
                        launch { offsetY.animateTo(0f, anim) }
                    }
                }
            }
        }
    }

    /**
     * Opens a video that is not on screen yet, growing the player out of [origin]. The origin is set
     * before the coroutine runs so an [expand] issued in the same frame leaves this open alone.
     */
    fun open(origin: SheetOpenOrigin) {
        corner = MiniPlayerCorner.BottomRight
        tuckedSide = null
        openOrigin = origin
        posterPlaceholderKey = (origin as? SheetOpenOrigin.Thumbnail)?.imageKey
        snapPortraitFullscreen(0f)
        val generation = ++openGeneration
        scope.launch {
            isShrinkingToCorner = false
            try {
                motion.snapSize(1f)
                motion.snapOffsets(x = cachedTargetX, y = cachedTargetY)
                motion.animateFraction {
                    expandFraction.snapTo(1f)
                    withSteadyFrames { expandFraction.animateTo(0f, playerOpenSpringSpec) }
                }
            } finally {
                if (generation == openGeneration) openOrigin = null
            }
        }
    }

    /**
     * Expand the floating mini player to wide mode.
     */
    fun expandWide(
        screenWidth: Float = 0f,
        margin: Float = 0f,
        baseMiniWidth: Float = 0f,
        screenHeight: Float = 0f,
        minY: Float = 0f,
        bottomNavPad: Float = 0f,
        isLargeWindow: Boolean = false,
    ) {
        val maxWideFraction = if (isLargeWindow) 0.60f else 1.00f
        val maxWideWidth =
            ((screenWidth * maxWideFraction) - (margin * 2f))
                .coerceAtLeast(baseMiniWidth)
        val effectiveBase = baseMiniWidth.coerceAtLeast(1f)
        val targetScale = (maxWideWidth / effectiveBase).coerceAtLeast(1f)
        val targetWidth = (effectiveBase * targetScale).coerceAtMost(maxWideWidth)
        val targetHeight = targetWidth * (9f / 16f)
        val targetMaxY =
            if (screenHeight > 0f) {
                (screenHeight - targetHeight - bottomNavPad - margin).coerceAtLeast(minY)
            } else {
                offsetY.value
            }

        val targetX =
            if (isLargeWindow) {
                val newMaxX =
                    (screenWidth - targetWidth - margin)
                        .coerceAtLeast(margin)
                offsetX.value.coerceIn(margin, newMaxX)
            } else {
                ((screenWidth - targetWidth) / 2f).coerceAtLeast(margin)
            }
        val targetY =
            if (screenHeight > 0f) {
                offsetY.value.coerceIn(minY, targetMaxY)
            } else {
                offsetY.value
            }

        scope.launch {
            isShrinkingToCorner = false
            launch { motion.resize { miniSizeScale.animateTo(targetScale, miniResizeSpringSpec) } }
            launch {
                motion.moveOffsets {
                    launch { offsetX.animateTo(targetX, miniResizeSpringSpec) }
                    launch { offsetY.animateTo(targetY, miniResizeSpringSpec) }
                }
            }
        }
    }

    /** [velocity] is the release speed in fraction per second, as for [expand]. */
    fun collapse(velocity: Float? = null) {
        scope.launch {
            isShrinkingToCorner = false
            val anim = playerCollapseSpringSpec
            if (cachedTargetX == 0f && cachedTargetY == 0f) {
                launch { motion.snapFraction(1f) }
            } else {
                launch {
                    withSteadyFrames {
                        launch {
                            motion.animateFraction {
                                expandFraction.animateTo(1f, anim, initialVelocity = velocity ?: expandFraction.velocity)
                            }
                        }
                        launch {
                            motion.moveOffsets {
                                launch { offsetX.animateTo(cachedTargetX, anim) }
                                launch { offsetY.animateTo(cachedTargetY, anim) }
                            }
                        }
                    }
                }
            }
            launch { motion.resize { miniSizeScale.animateTo(1f, anim) } }
        }
    }

    fun shrinkToCorner(
        baseMiniWidth: Float,
        screenWidth: Float,
        margin: Float,
        minY: Float,
        screenHeight: Float,
        bottomNavPad: Float,
    ) {
        val normalMiniWidth = baseMiniWidth
        val normalMiniHeight = normalMiniWidth * (9f / 16f)
        val normalMaxX = (screenWidth - normalMiniWidth - margin).coerceAtLeast(margin)
        val normalMaxY = (screenHeight - normalMiniHeight - bottomNavPad - margin).coerceAtLeast(minY)

        val targetX = cornerTargetX(corner, minX = margin, maxX = normalMaxX)
        val targetY = cornerTargetY(corner, minY = minY, maxY = normalMaxY)

        cachedTargetX = targetX
        cachedTargetY = targetY
        scope.launch {
            isShrinkingToCorner = true
            val anim = miniResizeSpringSpec
            try {
                val jobs =
                    listOf(
                        launch { motion.resize { miniSizeScale.animateTo(1f, anim) } },
                        launch {
                            motion.moveOffsets {
                                launch { offsetX.animateTo(targetX, anim) }
                                launch { offsetY.animateTo(targetY, anim) }
                            }
                        },
                    )
                jobs.forEach { it.join() }
            } finally {
                isShrinkingToCorner = false
            }
        }
    }

    /**
     * Predictive back scrubs the collapse the way a finger does: the mini offsets snap to the
     * resting corner first, then the fraction follows the gesture. Commit with [collapse], cancel
     * with [expand].
     */
    suspend fun beginBackScrub() {
        motion.stopFraction()
        motion.snapOffsets(x = cachedTargetX, y = cachedTargetY)
    }

    suspend fun scrubBack(progress: Float) {
        motion.snapFraction(progress.coerceIn(0f, 1f))
    }

    /** Cold-start restore: seeds never-placed offsets before first draw; the debounced resnap lands frames late. */
    internal fun seedMiniOffsets(
        x: Float,
        y: Float,
    ) {
        if (isDragging || expandFraction.value < 1f || expandFraction.isRunning || offsetX.value != 0f || offsetY.value != 0f) return
        if (x <= 0f || y <= 0f) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) { motion.snapOffsets(x, y) }
    }

    internal fun snapPortraitFullscreen(value: Float) {
        portraitFsJob?.cancel()
        portraitFsFraction = value
    }

    /** Runs the portrait-fullscreen fraction to [target] from wherever it is, at [velocity] per second. */
    internal suspend fun animatePortraitFullscreen(
        target: Float,
        velocity: Float,
    ) {
        portraitFsJob?.cancel()
        runPortraitFullscreen(target, velocity)
    }

    private suspend fun runPortraitFullscreen(
        target: Float,
        velocity: Float,
    ) {
        withSteadyFrames {
            animate(portraitFsFraction, target, velocity, portraitFullscreenSettleSpec) { value, _ ->
                portraitFsFraction = value
            }
        }
    }

    /** Slides the video back to the top of the page unless a hand-off already started that slide. */
    internal fun settlePortraitFullscreen() {
        if (portraitFsJob?.isActive == true || portraitFsFraction == 0f) return
        portraitFsJob = scope.launch { runPortraitFullscreen(0f, 0f) }
    }

    /**
     * Leaves portrait fullscreen from where an exit drag let the video go, [offsetPx] below the centre
     * and moving at [velocityPx] per second, so the slide back to the page continues the finger.
     */
    fun exitPortraitFullscreenFrom(
        offsetPx: Float,
        velocityPx: Float,
    ) {
        val travel = portraitFsTravel.coerceAtLeast(1f)
        snapPortraitFullscreen(1f + offsetPx / travel)
        portraitFsJob = scope.launch { runPortraitFullscreen(0f, velocityPx / travel) }
    }

    fun snapTo(target: PlayerSheetValue) {
        scope.launch {
            val targetF = if (target == PlayerSheetValue.Collapsed) 1f else 0f
            motion.snapFraction(targetF)
            if (target == PlayerSheetValue.Expanded) {
                motion.snapOffsets(x = 0f, y = 0f)
            }
        }
    }
}

@Composable
fun rememberPlayerDraggableState(): PlayerDraggableState {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val expandFraction = remember { Animatable(1f) }

    return remember {
        PlayerDraggableState(offsetX, offsetY, expandFraction, scope)
    }
}
