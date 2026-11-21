package io.github.aedev.flow.ui.theme

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset

/**
 * Motion for moving between screens. The theme's motion scheme has no spec that fits: its spatial
 * springs overshoot, and a page sliding the full width of the window overshoots into a strip of
 * the page beside it.
 */
object NavigationMotion {
    /** A page sliding in or out: critically damped, settling in about 250 ms. */
    val PageSlide: FiniteAnimationSpec<IntOffset> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = PAGE_SLIDE_STIFFNESS, visibilityThreshold = IntOffset(1, 1))

    /** The same slide on a low-RAM phone or with battery saver on: over in about 150 ms, so fewer frames are drawn. */
    val PageSlideBrisk: FiniteAnimationSpec<IntOffset> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = PAGE_SLIDE_BRISK_STIFFNESS, visibilityThreshold = IntOffset(1, 1))

    /** Artwork flying from a card to the cover of the page it opened, in step with the page slide. */
    val ArtworkBounds: FiniteAnimationSpec<Rect> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = PAGE_SLIDE_STIFFNESS, visibilityThreshold = Rect.VisibilityThreshold)

    /** Material's fade-through between top-level destinations: the old one leaves in the first 30%. */
    val TabFadeOut: FiniteAnimationSpec<Float> = tween(TAB_FADE_OUT_MS, easing = FastOutLinearInEasing)

    /** The new destination of a fade-through, arriving over the remaining 70%. */
    val TabFadeIn: FiniteAnimationSpec<Float> =
        tween(TAB_FADE_TOTAL_MS - TAB_FADE_OUT_MS, delayMillis = TAB_FADE_OUT_MS, easing = LinearOutSlowInEasing)
}

private const val PAGE_SLIDE_STIFFNESS = 500f
private const val PAGE_SLIDE_BRISK_STIFFNESS = 1400f
private const val TAB_FADE_TOTAL_MS = 300
private const val TAB_FADE_OUT_MS = 90
