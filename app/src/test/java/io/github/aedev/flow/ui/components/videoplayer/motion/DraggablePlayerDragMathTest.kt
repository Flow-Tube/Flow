package io.github.aedev.flow.ui.components.videoplayer.motion

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.ui.components.videoplayer.MiniPlayerCorner
import io.github.aedev.flow.ui.components.videoplayer.MiniPlayerTuckSide
import org.junit.Test

class DraggablePlayerDragMathTest {
    private val t = PlayerGestureThresholds(density = 3f)
    private val bounds = MiniPlayerBounds(minX = 24f, maxX = 624f, minY = 200f, maxY = 1400f)

    @Test
    fun `expand drag zoom rests at one and never reaches its ceiling`() {
        assertThat(expandDragZoomFor(0f, thresholds = t)).isEqualTo(1f)
        assertThat(expandDragZoomFor(-10f, thresholds = t)).isEqualTo(1f)
        assertThat(expandDragZoomFor(60f, thresholds = t)).isWithin(0.0001f).of(1.03f)
        assertThat(expandDragZoomFor(10_000f, thresholds = t)).isLessThan(1.06f)
        assertThat(expandDragZoomFor(300f, thresholds = t)).isGreaterThan(expandDragZoomFor(100f, thresholds = t))
    }

    @Test
    fun `upward swipe commits to fullscreen on travel or on a fast fling`() {
        assertThat(shouldEnterFullscreenFromSwipe(totalUpwardDragPx = 81f, scaledVelocityY = 0f, thresholds = t)).isTrue()
        assertThat(shouldEnterFullscreenFromSwipe(totalUpwardDragPx = 10f, scaledVelocityY = -900f, thresholds = t)).isTrue()
        assertThat(shouldEnterFullscreenFromSwipe(totalUpwardDragPx = 40f, scaledVelocityY = -200f, thresholds = t)).isFalse()
    }

    @Test
    fun `collapse release needs travel or a downward fling`() {
        assertThat(shouldCollapseOnRelease(fraction = 0.21f, scaledVelocityY = 0f, thresholds = t)).isTrue()
        assertThat(shouldCollapseOnRelease(fraction = 0.02f, scaledVelocityY = 1100f, thresholds = t)).isTrue()
        assertThat(shouldCollapseOnRelease(fraction = 0.12f, scaledVelocityY = 600f, thresholds = t)).isFalse()
        assertThat(shouldCollapseOnRelease(fraction = 0.06f, scaledVelocityY = 350f, thresholds = t)).isFalse()
        assertThat(shouldCollapseOnRelease(fraction = 0.15f, scaledVelocityY = -100f, thresholds = t)).isFalse()
    }

    @Test
    fun `a flick back up cancels a collapse however far it travelled`() {
        assertThat(shouldCollapseOnRelease(fraction = 0.3f, scaledVelocityY = -2000f, thresholds = t)).isFalse()
        assertThat(shouldCollapseOnRelease(fraction = 0.9f, scaledVelocityY = -1100f, thresholds = t)).isFalse()
        assertThat(shouldCollapseOnRelease(fraction = 0.3f, scaledVelocityY = -900f, thresholds = t)).isTrue()
    }

    @Test
    fun `a slow release past the commit point and a fast flick down both collapse`() {
        assertThat(shouldCollapseOnRelease(fraction = 0.5f, scaledVelocityY = -50f, thresholds = t)).isTrue()
        assertThat(shouldCollapseOnRelease(fraction = 0.05f, scaledVelocityY = 3000f, thresholds = t)).isTrue()
    }

    @Test
    fun `portrait fullscreen commits on travel or a downward fling`() {
        assertThat(shouldEnterPortraitFullscreen(fraction = 0.3f, velocityY = 0f, thresholds = t)).isTrue()
        assertThat(shouldEnterPortraitFullscreen(fraction = 0.1f, velocityY = 2600f, thresholds = t)).isTrue()
        assertThat(shouldEnterPortraitFullscreen(fraction = 0.1f, velocityY = 1500f, thresholds = t)).isFalse()
        assertThat(shouldEnterPortraitFullscreen(fraction = 0.2f, velocityY = 0f, thresholds = t)).isFalse()
    }

    @Test
    fun `a flick back up takes the pull back however far it travelled`() {
        assertThat(shouldEnterPortraitFullscreen(fraction = 0.9f, velocityY = -1500f, thresholds = t)).isFalse()
        assertThat(shouldEnterPortraitFullscreen(fraction = 0.9f, velocityY = -900f, thresholds = t)).isTrue()
    }

    @Test
    fun `settle velocity is the fling in fractions of the travel`() {
        assertThat(fractionVelocity(velocityY = 2000f, travelPx = 2000f)).isEqualTo(1f)
        assertThat(fractionVelocity(velocityY = -1000f, travelPx = 2000f)).isEqualTo(-0.5f)
        assertThat(fractionVelocity(velocityY = 800f, travelPx = 0f)).isEqualTo(800f)
    }

    @Test
    fun `corner targets follow the corner`() {
        assertThat(cornerTargetX(MiniPlayerCorner.TopLeft, minX = 24f, maxX = 624f)).isEqualTo(24f)
        assertThat(cornerTargetX(MiniPlayerCorner.BottomRight, minX = 24f, maxX = 624f)).isEqualTo(624f)
        assertThat(cornerTargetY(MiniPlayerCorner.TopRight, minY = 200f, maxY = 1400f)).isEqualTo(200f)
        assertThat(cornerTargetY(MiniPlayerCorner.BottomLeft, minY = 200f, maxY = 1400f)).isEqualTo(1400f)
    }

    @Test
    fun `a dominant horizontal fling picks the side regardless of position`() {
        val corner =
            resolveMiniPlayerCorner(
                current = MiniPlayerCorner.BottomRight,
                currentX = 600f,
                currentY = 1380f,
                bounds = bounds,
                scaledVelocityX = -1500f,
                scaledVelocityY = 100f,
                thresholds = t,
            )
        assertThat(corner).isEqualTo(MiniPlayerCorner.BottomLeft)
    }

    @Test
    fun `a dominant vertical fling flips top and bottom`() {
        val corner =
            resolveMiniPlayerCorner(
                current = MiniPlayerCorner.BottomRight,
                currentX = 600f,
                currentY = 1380f,
                bounds = bounds,
                scaledVelocityX = 50f,
                scaledVelocityY = -1200f,
                thresholds = t,
            )
        assertThat(corner).isEqualTo(MiniPlayerCorner.TopRight)
    }

    @Test
    fun `a slow release switches only past fifteen percent of the travel`() {
        val stays =
            resolveMiniPlayerCorner(
                current = MiniPlayerCorner.BottomRight,
                currentX = 560f,
                currentY = 1400f,
                bounds = bounds,
                scaledVelocityX = 0f,
                scaledVelocityY = 0f,
                thresholds = t,
            )
        assertThat(stays).isEqualTo(MiniPlayerCorner.BottomRight)

        val switches =
            resolveMiniPlayerCorner(
                current = MiniPlayerCorner.BottomRight,
                currentX = 500f,
                currentY = 1400f,
                bounds = bounds,
                scaledVelocityX = 0f,
                scaledVelocityY = 0f,
                thresholds = t,
            )
        assertThat(switches).isEqualTo(MiniPlayerCorner.BottomLeft)
    }

    @Test
    fun `velocity projection can switch a corner the position alone would not`() {
        val corner =
            resolveMiniPlayerCorner(
                current = MiniPlayerCorner.BottomRight,
                currentX = 600f,
                currentY = 1400f,
                bounds = bounds,
                scaledVelocityX = -350f,
                scaledVelocityY = 0f,
                thresholds = t,
            )
        assertThat(corner).isEqualTo(MiniPlayerCorner.BottomLeft)
    }

    @Test
    fun `inside the bounds the mini player stays under the finger`() {
        assertThat(rubberBand(500f, 100f, 900f)).isEqualTo(500f)
    }

    @Test
    fun `past a bound the mini player follows a third of the overshoot`() {
        assertThat(rubberBand(1100f, 100f, 900f)).isWithin(0.01f).of(970f)
        assertThat(rubberBand(0f, 100f, 900f)).isWithin(0.01f).of(65f)
    }

    @Test
    fun `pulling well below the lowest corner closes the mini player`() {
        assertThat(
            shouldCloseMiniDownward(1500f, 1400f, 200f, startedAtBottom = false, velocityX = 0f, velocityY = 0f, thresholds = t),
        ).isTrue()
        assertThat(
            shouldCloseMiniDownward(1460f, 1400f, 200f, startedAtBottom = true, velocityX = 0f, velocityY = 0f, thresholds = t),
        ).isFalse()
    }

    @Test
    fun `a downward flick closes it only from a bottom corner`() {
        assertThat(
            shouldCloseMiniDownward(1400f, 1400f, 200f, startedAtBottom = true, velocityX = 100f, velocityY = 1500f, thresholds = t),
        ).isTrue()
        assertThat(
            shouldCloseMiniDownward(600f, 1400f, 200f, startedAtBottom = false, velocityX = 100f, velocityY = 1500f, thresholds = t),
        ).isFalse()
    }

    @Test
    fun `a diagonal flick moves to a corner instead of closing`() {
        assertThat(
            shouldCloseMiniDownward(1400f, 1400f, 200f, startedAtBottom = true, velocityX = 1200f, velocityY = 1500f, thresholds = t),
        ).isFalse()
    }

    private val tuckBounds = MiniPlayerBounds(minX = 24f, maxX = 570f, minY = 272f, maxY = 2200f)

    private fun tuck(
        fingerX: Float,
        startedOnLeft: Boolean,
        velocityX: Float = 0f,
        velocityY: Float = 0f,
    ) = resolveMiniPlayerTuck(
        fingerX,
        startedOnLeft,
        tuckBounds,
        miniWidth = 486f,
        velocityX = velocityX,
        velocityY = velocityY,
        thresholds = t,
    )

    @Test
    fun `dragging half its width past a side tucks the mini player into it`() {
        assertThat(tuck(fingerX = 830f, startedOnLeft = false))
            .isEqualTo(MiniPlayerTuckSide.Right)
        assertThat(tuck(fingerX = -230f, startedOnLeft = true))
            .isEqualTo(MiniPlayerTuckSide.Left)
    }

    @Test
    fun `a push less than half its width past the side snaps back instead of tucking`() {
        assertThat(tuck(fingerX = 780f, startedOnLeft = false))
            .isNull()
    }

    @Test
    fun `a sideways fling tucks only out through the side it started on`() {
        assertThat(tuck(fingerX = 620f, startedOnLeft = false, velocityX = 3000f, velocityY = 200f))
            .isEqualTo(MiniPlayerTuckSide.Right)
        assertThat(tuck(fingerX = 620f, startedOnLeft = true, velocityX = 3000f, velocityY = 200f))
            .isNull()
    }

    @Test
    fun `an ordinary sideways throw stays on screen`() {
        assertThat(tuck(fingerX = 620f, startedOnLeft = false, velocityX = 1800f, velocityY = 100f))
            .isNull()
    }

    @Test
    fun `a diagonal fling picks a corner rather than tucking`() {
        assertThat(tuck(fingerX = 560f, startedOnLeft = false, velocityX = 3000f, velocityY = 1500f))
            .isNull()
    }

    @Test
    fun `a tucked mini player rests wholly past its edge`() {
        assertThat(tuckedMiniX(MiniPlayerTuckSide.Right, screenWidth = 1080f, miniWidth = 486f)).isEqualTo(1080f)
        assertThat(tuckedMiniX(MiniPlayerTuckSide.Left, screenWidth = 1080f, miniWidth = 486f)).isEqualTo(-486f)
    }
}
