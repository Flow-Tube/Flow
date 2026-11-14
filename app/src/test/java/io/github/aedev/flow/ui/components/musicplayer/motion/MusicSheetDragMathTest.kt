package io.github.aedev.flow.ui.components.musicplayer.motion

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MusicSheetDragMathTest {
    @Test
    fun `dragging up past the threshold expands`() {
        assertThat(
            resolveMusicSheetDragTarget(
                isExpanded = false,
                accumulatedDragY = -40f,
                minDragThresholdPx = 12f,
                verticalVelocity = 0f,
                velocityThreshold = 150f,
                currentFraction = 0.1f,
            ),
        ).isTrue()
    }

    @Test
    fun `dragging down past the threshold collapses`() {
        assertThat(
            resolveMusicSheetDragTarget(
                isExpanded = true,
                accumulatedDragY = 40f,
                minDragThresholdPx = 12f,
                verticalVelocity = 0f,
                velocityThreshold = 150f,
                currentFraction = 0.9f,
            ),
        ).isFalse()
    }

    @Test
    fun `an expanded sheet dragged only upward stays expanded`() {
        assertThat(
            resolveMusicSheetDragTarget(
                isExpanded = true,
                accumulatedDragY = -300f,
                minDragThresholdPx = 12f,
                verticalVelocity = 900f,
                velocityThreshold = 150f,
                currentFraction = 1f,
            ),
        ).isTrue()
    }

    @Test
    fun `a fast upward fling expands even with a tiny drag`() {
        assertThat(
            resolveMusicSheetDragTarget(
                isExpanded = false,
                accumulatedDragY = -4f,
                minDragThresholdPx = 12f,
                verticalVelocity = -400f,
                velocityThreshold = 150f,
                currentFraction = 0.05f,
            ),
        ).isTrue()
    }

    @Test
    fun `a slow release settles to the nearest anchor`() {
        assertThat(
            resolveMusicSheetDragTarget(
                isExpanded = false,
                accumulatedDragY = 2f,
                minDragThresholdPx = 12f,
                verticalVelocity = 10f,
                velocityThreshold = 150f,
                currentFraction = 0.7f,
            ),
        ).isTrue()
        assertThat(
            resolveMusicSheetDragTarget(
                isExpanded = false,
                accumulatedDragY = 2f,
                minDragThresholdPx = 12f,
                verticalVelocity = 10f,
                velocityThreshold = 150f,
                currentFraction = 0.3f,
            ),
        ).isFalse()
    }

    @Test
    fun `drag frame couples position and fraction linearly`() {
        val frame =
            computeMusicSheetDragFrame(
                currentTranslationY = 800f,
                dragAmount = -400f,
                expandedY = 0f,
                collapsedY = 800f,
                miniHeightPx = 100f,
                initialFractionOnDragStart = 0f,
                initialYOnDragStart = 800f,
            )
        assertThat(frame.translationY).isEqualTo(400f)
        assertThat(frame.expansionFraction).isEqualTo(0.5f)
    }

    @Test
    fun `drag frame rubber-bands past the anchors`() {
        val above =
            computeMusicSheetDragFrame(
                currentTranslationY = 0f,
                dragAmount = -500f,
                expandedY = 0f,
                collapsedY = 800f,
                miniHeightPx = 100f,
                initialFractionOnDragStart = 1f,
                initialYOnDragStart = 0f,
            )
        assertThat(above.translationY).isEqualTo(-20f)
        assertThat(above.expansionFraction).isEqualTo(1f)

        val below =
            computeMusicSheetDragFrame(
                currentTranslationY = 800f,
                dragAmount = 500f,
                expandedY = 0f,
                collapsedY = 800f,
                miniHeightPx = 100f,
                initialFractionOnDragStart = 0f,
                initialYOnDragStart = 800f,
            )
        assertThat(below.translationY).isEqualTo(820f)
        assertThat(below.expansionFraction).isEqualTo(0f)
    }

    @Test
    fun `collapse damping and squash scale with the fraction`() {
        assertThat(collapseSpringDampingForFraction(0f)).isEqualTo(1f)
        assertThat(collapseSpringDampingForFraction(1f)).isEqualTo(0.75f)
        assertThat(collapseInitialSquashForFraction(0f)).isEqualTo(1f)
        assertThat(collapseInitialSquashForFraction(1f)).isEqualTo(0.97f)
    }

    private fun closes(
        startedCollapsed: Boolean,
        pulled: Float,
        velocity: Float,
    ) = shouldCloseMusicMiniDownward(startedCollapsed, pulled, miniHeightPx = 64f, verticalVelocity = velocity, closeFlingVelocity = 800f)

    @Test
    fun `a collapsed bar pulled half its height down closes`() {
        assertThat(closes(true, pulled = 40f, velocity = 0f)).isTrue()
        assertThat(closes(true, pulled = 20f, velocity = 0f)).isFalse()
    }

    @Test
    fun `a downward flick on the collapsed bar closes it`() {
        assertThat(closes(true, pulled = 10f, velocity = 1200f)).isTrue()
    }

    @Test
    fun `a drag from the expanded player never closes`() {
        assertThat(closes(false, pulled = 900f, velocity = 3000f)).isFalse()
    }

    @Test
    fun `only a collapsed bar can be pulled far below its resting place`() {
        val pulled = computeMusicSheetDragFrame(1000f, 200f, 0f, 1000f, 64f, 0f, 1000f, canPullToClose = true)
        val held = computeMusicSheetDragFrame(1000f, 200f, 0f, 1000f, 64f, 0f, 1000f)

        assertThat(pulled.translationY).isEqualTo(1096f)
        assertThat(held.translationY).isWithin(0.01f).of(1012.8f)
    }
}
