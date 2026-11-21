package io.github.aedev.flow.ui.theme

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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

    /** Material's fade-through between top-level destinations: the old one leaves in the first 30%. */
    val TabFadeOut: FiniteAnimationSpec<Float> = tween(TAB_FADE_OUT_MS, easing = FastOutLinearInEasing)

    /** The new destination of a fade-through, arriving over the remaining 70%. */
    val TabFadeIn: FiniteAnimationSpec<Float> =
        tween(TAB_FADE_TOTAL_MS - TAB_FADE_OUT_MS, delayMillis = TAB_FADE_OUT_MS, easing = LinearOutSlowInEasing)
}

private const val PAGE_SLIDE_STIFFNESS = 500f
private const val TAB_FADE_TOTAL_MS = 300
private const val TAB_FADE_OUT_MS = 90
