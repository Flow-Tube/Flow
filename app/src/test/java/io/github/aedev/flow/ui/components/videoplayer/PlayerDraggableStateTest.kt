package io.github.aedev.flow.ui.components.videoplayer

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.BroadcastFrameClock
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.ui.components.videoplayer.motion.MiniPlayerResnapTargets
import io.github.aedev.flow.ui.components.videoplayer.motion.resnapMiniPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Drives [PlayerDraggableState] under a manual frame clock so every settle, preemption and
 * gesture hand-off the layout relies on can be asserted without a device. The invariants here
 * are the ones the pre-refactor single-file implementation had; a change that breaks one of them
 * changes how the player feels or, as with the mid-morph freeze, whether it works at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerDraggableStateTest {
    private val clock = BroadcastFrameClock()
    private var frameNanos = 0L

    private fun TestScope.newState(collapsed: Boolean): PlayerDraggableState {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]) + clock)
        return PlayerDraggableState(
            offsetX = Animatable(0f),
            offsetY = Animatable(0f),
            expandFraction = Animatable(if (collapsed) 1f else 0f),
            scope = scope,
        )
    }

    /** Pumps frames until nothing on the state animates any more, or the frame budget runs out. */
    private fun TestScope.settle(
        state: PlayerDraggableState,
        maxFrames: Int = 600,
    ) {
        repeat(maxFrames) {
            runCurrent()
            if (!state.isAnimating()) return
            frameNanos += FRAME_NANOS
            clock.sendFrame(frameNanos)
        }
        runCurrent()
    }

    private fun TestScope.pumpFrames(count: Int) {
        repeat(count) {
            runCurrent()
            frameNanos += FRAME_NANOS
            clock.sendFrame(frameNanos)
        }
        runCurrent()
    }

    private fun PlayerDraggableState.isAnimating(): Boolean =
        expandFraction.isRunning || offsetX.isRunning || offsetY.isRunning || miniSizeScale.isRunning

    private fun phoneTargets(
        x: Float = 570f,
        y: Float = 2200f,
    ) = MiniPlayerResnapTargets(
        isWideMode = false,
        isLargeScreen = false,
        targetMiniX = x,
        targetMiniY = y,
        minX = 24f,
        maxX = x,
        minY = 272f,
        stableWideMaxY = 2000f,
        stablePhoneCenteredX = 24f,
        stableWideTargetY = 2000f,
    )

    @Test
    fun `collapse completes even when the mini player is re-snapped mid-flight`() =
        runTest {
            val state = newState(collapsed = false)
            state.cachedTargetX = 570f
            state.cachedTargetY = 2200f

            state.collapse()
            pumpFrames(4)
            assertThat(state.expandFraction.value).isGreaterThan(0f)
            assertThat(state.expandFraction.value).isLessThan(1f)

            // The layout's re-snap effect fires as soon as the target flips to collapsed.
            state.scope.launch { resnapMiniPlayer(state, phoneTargets()) }
            settle(state)

            assertThat(state.expandFraction.value).isEqualTo(1f)
            assertThat(state.offsetX.value).isEqualTo(570f)
            assertThat(state.offsetY.value).isEqualTo(2200f)
            assertThat(state.currentValue).isEqualTo(PlayerSheetValue.Collapsed)
            state.scope.cancel()
        }

    @Test
    fun `a long frame pauses a collapse instead of skipping it ahead`() =
        runTest {
            val state = newState(collapsed = false)
            state.cachedTargetX = 570f
            state.cachedTargetY = 2200f

            state.collapse()
            pumpFrames(2)
            val beforeLongFrame = state.expandFraction.value
            frameNanos += 200_000_000L
            clock.sendFrame(frameNanos)
            runCurrent()

            assertThat(state.expandFraction.value).isGreaterThan(beforeLongFrame)
            assertThat(state.expandFraction.value).isLessThan(0.6f)
            state.scope.cancel()
        }

    @Test
    fun `an open grows from its origin and lets go of it once expanded`() =
        runTest {
            val state = newState(collapsed = true)

            state.open(SheetOpenOrigin.BelowScreen)
            assertThat(state.openOrigin).isEqualTo(SheetOpenOrigin.BelowScreen)
            pumpFrames(3)
            assertThat(state.expandFraction.value).isLessThan(1f)
            assertThat(state.currentValue).isEqualTo(PlayerSheetValue.Expanded)

            settle(state)
            assertThat(state.expandFraction.value).isEqualTo(0f)
            assertThat(state.openOrigin).isNull()
            state.scope.cancel()
        }

    @Test
    fun `an expand while an open is running leaves the open alone`() =
        runTest {
            val state = newState(collapsed = true)

            state.open(SheetOpenOrigin.BelowScreen)
            state.expand()
            pumpFrames(3)

            assertThat(state.openOrigin).isEqualTo(SheetOpenOrigin.BelowScreen)
            settle(state)
            assertThat(state.openOrigin).isNull()
            state.scope.cancel()
        }

    @Test
    fun `a collapse during an open drops the origin`() =
        runTest {
            val state = newState(collapsed = true)
            state.cachedTargetX = 570f
            state.cachedTargetY = 2200f

            state.open(SheetOpenOrigin.BelowScreen)
            pumpFrames(3)
            state.collapse()
            settle(state)

            assertThat(state.openOrigin).isNull()
            assertThat(state.expandFraction.value).isEqualTo(1f)
            state.scope.cancel()
        }

    @Test
    fun `isSettled waits for the finger, the fraction and the offsets`() =
        runTest {
            val state = newState(collapsed = false)
            state.cachedTargetX = 570f
            state.cachedTargetY = 2200f
            assertThat(state.isSettled).isTrue()

            state.collapse()
            pumpFrames(4)
            assertThat(state.isSettled).isFalse()

            settle(state)
            assertThat(state.isSettled).isTrue()

            state.isDragging = true
            assertThat(state.isSettled).isFalse()
            state.scope.cancel()
        }

    @Test
    fun `a released fling carries its speed into the collapse`() =
        runTest {
            val thrown = newState(collapsed = false)
            thrown.cachedTargetX = 570f
            thrown.cachedTargetY = 2200f
            val dropped = newState(collapsed = false)
            dropped.cachedTargetX = 570f
            dropped.cachedTargetY = 2200f

            thrown.collapse(velocity = 4f)
            dropped.collapse(velocity = 0f)
            pumpFrames(3)

            assertThat(thrown.expandFraction.value).isGreaterThan(dropped.expandFraction.value)
            thrown.scope.cancel()
            dropped.scope.cancel()
        }

    @Test
    fun `an expand mid-collapse turns the sheet around and lands expanded`() =
        runTest {
            val state = newState(collapsed = false)
            state.cachedTargetX = 570f
            state.cachedTargetY = 2200f

            state.collapse()
            pumpFrames(8)
            val turnedAt = state.expandFraction.value
            assertThat(turnedAt).isGreaterThan(0f)

            state.expand()
            settle(state)
            assertThat(state.expandFraction.value).isEqualTo(0f)
            state.scope.cancel()
        }

    @Test
    fun `collapse with no known corner snaps straight to mini`() =
        runTest {
            val state = newState(collapsed = false)

            state.collapse()
            runCurrent()

            assertThat(state.expandFraction.value).isEqualTo(1f)
            assertThat(state.expandFraction.isRunning).isFalse()
            state.scope.cancel()
        }

    @Test
    fun `expand returns to the expanded anchor and resets the corner`() =
        runTest {
            val state = newState(collapsed = true)
            state.corner = MiniPlayerCorner.TopLeft
            state.offsetX.snapTo(24f)
            state.offsetY.snapTo(272f)

            state.expand()
            settle(state)

            assertThat(state.expandFraction.value).isEqualTo(0f)
            assertThat(state.offsetX.value).isEqualTo(0f)
            assertThat(state.offsetY.value).isEqualTo(0f)
            assertThat(state.corner).isEqualTo(MiniPlayerCorner.BottomRight)
            assertThat(state.currentValue).isEqualTo(PlayerSheetValue.Expanded)
            state.scope.cancel()
        }

    @Test
    fun `a mini drag start stops the corner settle but not a running collapse`() =
        runTest {
            val state = newState(collapsed = false)
            state.cachedTargetX = 570f
            state.cachedTargetY = 2200f

            state.collapse()
            pumpFrames(6)
            assertThat(state.expandFraction.value).isLessThan(1f)

            state.motion.stopOffsets()
            val frozenX = state.offsetX.value
            settle(state)

            assertThat(state.expandFraction.value).isEqualTo(1f)
            assertThat(state.offsetX.value).isEqualTo(frozenX)
            state.scope.cancel()
        }

    @Test
    fun `a collapse drag start freezes the fraction and pins the offsets to the resting corner`() =
        runTest {
            val state = newState(collapsed = true)
            state.expand()
            pumpFrames(6)
            val fractionAtTouch = state.expandFraction.value
            assertThat(fractionAtTouch).isGreaterThan(0f)

            state.motion.stopFraction()
            state.motion.snapOffsets(x = 570f, y = 2200f)
            pumpFrames(20)

            assertThat(state.expandFraction.value).isEqualTo(fractionAtTouch)
            assertThat(state.expandFraction.isRunning).isFalse()
            assertThat(state.offsetX.value).isEqualTo(570f)
            assertThat(state.offsetY.value).isEqualTo(2200f)
            state.scope.cancel()
        }

    @Test
    fun `a resize keeps running while the offsets are taken over by a drag`() =
        runTest {
            val state = newState(collapsed = true)
            state.offsetX.snapTo(570f)
            state.offsetY.snapTo(2200f)

            state.expandWide(
                screenWidth = 1220f,
                margin = 24f,
                baseMiniWidth = 549f,
                screenHeight = 2712f,
                minY = 272f,
                bottomNavPad = 200f,
            )
            pumpFrames(5)
            assertThat(state.miniSizeScale.value).isGreaterThan(1f)
            assertThat(state.miniSizeScale.isRunning).isTrue()

            state.motion.stopOffsets()
            settle(state)

            assertThat(state.miniSizeScale.value).isGreaterThan(1.5f)
            assertThat(state.isInlineMode).isTrue()
            state.scope.cancel()
        }

    @Test
    fun `shrinkToCorner clears its flag only once both moves have finished`() =
        runTest {
            val state = newState(collapsed = true)
            state.miniSizeScale.snapTo(2.1f)
            state.corner = MiniPlayerCorner.BottomLeft

            state.shrinkToCorner(
                baseMiniWidth = 549f,
                screenWidth = 1220f,
                margin = 24f,
                minY = 272f,
                screenHeight = 2712f,
                bottomNavPad = 200f,
            )
            pumpFrames(3)
            assertThat(state.isShrinkingToCorner).isTrue()

            settle(state)

            assertThat(state.isShrinkingToCorner).isFalse()
            assertThat(state.miniSizeScale.value).isEqualTo(1f)
            assertThat(state.offsetX.value).isEqualTo(24f)
            assertThat(state.cachedTargetX).isEqualTo(24f)
            state.scope.cancel()
        }

    @Test
    fun `a corner throw moves both offsets from one intent`() =
        runTest {
            val state = newState(collapsed = true)
            state.offsetX.snapTo(300f)
            state.offsetY.snapTo(1200f)

            state.scope.launch {
                state.motion.moveOffsets {
                    launch { state.offsetX.animateTo(24f) }
                    launch { state.offsetY.animateTo(272f) }
                }
            }
            settle(state)

            assertThat(state.offsetX.value).isEqualTo(24f)
            assertThat(state.offsetY.value).isEqualTo(272f)
            state.scope.cancel()
        }

    @Test
    fun `predictive back commits to mini and a cancelled scrub springs back`() =
        runTest {
            val state = newState(collapsed = false)
            state.cachedTargetX = 570f
            state.cachedTargetY = 2200f

            state.beginBackScrub()
            state.scrubBack(0.3f)
            assertThat(state.expandFraction.value).isEqualTo(0.3f)
            assertThat(state.offsetX.value).isEqualTo(570f)

            state.collapse()
            state.scope.launch { resnapMiniPlayer(state, phoneTargets()) }
            settle(state)
            assertThat(state.expandFraction.value).isEqualTo(1f)

            state.expand()
            settle(state)
            state.beginBackScrub()
            state.scrubBack(0.4f)
            state.expand()
            settle(state)
            assertThat(state.expandFraction.value).isEqualTo(0f)
            state.scope.cancel()
        }

    @Test
    fun `snapTo collapsed and expanded set the anchor without animating`() =
        runTest {
            val state = newState(collapsed = false)
            state.offsetX.snapTo(570f)

            state.snapTo(PlayerSheetValue.Collapsed)
            runCurrent()
            assertThat(state.expandFraction.value).isEqualTo(1f)
            assertThat(state.offsetX.value).isEqualTo(570f)

            state.snapTo(PlayerSheetValue.Expanded)
            runCurrent()
            assertThat(state.expandFraction.value).isEqualTo(0f)
            assertThat(state.offsetX.value).isEqualTo(0f)
            state.scope.cancel()
        }

    @Test
    fun `seedMiniOffsets places a restored collapsed mini player without a frame`() =
        runTest {
            val state = newState(collapsed = true)
            state.collapse()
            runCurrent()

            state.seedMiniOffsets(570f, 2200f)

            assertThat(state.offsetX.value).isEqualTo(570f)
            assertThat(state.offsetY.value).isEqualTo(2200f)
            state.scope.cancel()
        }

    @Test
    fun `seedMiniOffsets leaves an expanded or already placed player alone`() =
        runTest {
            val expanded = newState(collapsed = false)
            expanded.seedMiniOffsets(570f, 2200f)
            assertThat(expanded.offsetX.value).isEqualTo(0f)
            assertThat(expanded.offsetY.value).isEqualTo(0f)

            val placed = newState(collapsed = true)
            placed.offsetX.snapTo(300f)
            placed.offsetY.snapTo(1500f)
            placed.seedMiniOffsets(570f, 2200f)
            assertThat(placed.offsetX.value).isEqualTo(300f)
            assertThat(placed.offsetY.value).isEqualTo(1500f)
            expanded.scope.cancel()
            placed.scope.cancel()
        }

    private companion object {
        const val FRAME_NANOS = 16_000_000L
    }
}
