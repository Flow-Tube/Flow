package io.github.aedev.flow.ui.components.videoplayer

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import io.github.aedev.flow.ui.components.videoplayer.motion.BODY_CONTENT_MAX_EXPAND_FRACTION

/**
 * Keeps the content composed and measured but neither drawn nor hit-testable while [visible]
 * returns false. The read happens in the placement pass, so a flip re-places this node only.
 * Compose ships no visibility modifier: `alpha(0f)` still receives pointer input, and dropping the
 * content from composition is exactly the mount cost this exists to keep off the sheet motion.
 */
internal fun Modifier.placedWhen(visible: () -> Boolean): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            if (visible()) placeable.place(0, 0)
        }
    }

/**
 * Fades out with the page under the video over the first stretch of a collapse, then stops being
 * placed. [fraction] is the sheet's expand fraction and is read in the placement and draw passes
 * only. The alpha sits on this layer alone, never on an ancestor of the video surface.
 */
internal fun Modifier.fadedOutByCollapse(fraction: () -> Float): Modifier =
    placedWhen { fraction() < BODY_CONTENT_MAX_EXPAND_FRACTION }
        .graphicsLayer {
            alpha = (1f - fraction() / BODY_CONTENT_MAX_EXPAND_FRACTION).coerceIn(0f, 1f)
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
