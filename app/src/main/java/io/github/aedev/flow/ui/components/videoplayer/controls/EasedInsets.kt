package io.github.aedev.flow.ui.components.videoplayer.controls

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

private val NoInset: () -> Dp = { 0.dp }

/**
 * One of the control insets, eased between its fullscreen and windowed values (#1065) and handed
 * out as a function so it is read where it is used, in the layout phase, instead of recomposing the
 * whole overlay on every frame of the ease.
 *
 * On an effects spec, not a spatial one: the Expressive spatial springs overshoot their target,
 * and an inset that undershoots zero on the way down is a negative padding. The clamp keeps that
 * true even if the theme's spec is changed later.
 */
@Composable
internal fun rememberEasedInset(target: Dp): () -> Dp {
    val inset = remember { Animatable(target, Dp.VectorConverter) }
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Dp>()
    LaunchedEffect(target) { inset.animateTo(target, spec) }
    return remember(inset) { { inset.value.coerceAtLeast(0.dp) } }
}

/** [Modifier.padding] for insets given as functions, which are read in the layout phase only. */
internal fun Modifier.insetPadding(
    start: () -> Dp = NoInset,
    end: () -> Dp = NoInset,
    top: () -> Dp = NoInset,
    bottom: () -> Dp = NoInset,
): Modifier =
    layout { measurable, constraints ->
        val left = start().roundToPx()
        val right = end().roundToPx()
        val above = top().roundToPx()
        val below = bottom().roundToPx()
        val horizontal = left + right
        val vertical = above + below
        val placeable = measurable.measure(constraints.offset(-horizontal, -vertical))
        layout(
            constraints.constrainWidth(placeable.width + horizontal),
            constraints.constrainHeight(placeable.height + vertical),
        ) { placeable.placeRelative(left, above) }
    }

/** [insetPadding] with the same inset on both sides. */
internal fun Modifier.insetPadding(horizontal: () -> Dp): Modifier = insetPadding(start = horizontal, end = horizontal)
