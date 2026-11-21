package io.github.aedev.flow.ui.utils

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity

/**
 * Lets a page draw under the status bar. The app shell pads every page below the status bar, so
 * the page is measured [topPx] taller and placed that far up, keeping its own bottom edge.
 */
fun Modifier.reachUnderStatusBar(topPx: Int): Modifier =
    layout { measurable, constraints ->
        if (topPx == 0 || !constraints.hasBoundedHeight) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val height = constraints.maxHeight + topPx
        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(placeable.width, constraints.maxHeight) { placeable.place(0, -topPx) }
    }

/**
 * [reachUnderStatusBar] by the status bar's own height. Put it on a screen's outermost container: a
 * Scaffold clips its content to its own bounds, so a child reaching up on its own is cut off.
 */
@Composable
fun Modifier.reachUnderStatusBar(): Modifier = reachUnderStatusBar(WindowInsets.statusBars.getTop(LocalDensity.current))
