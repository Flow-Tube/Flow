package io.github.aedev.flow.ui.utils

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.toSize
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass

/**
 * The size the app's root is laid out at, which is the window the app draws in: split-screen and
 * freeform windows are measured by what they can show rather than by the display they sit on.
 *
 * Read it through [flowWindowDpSize], not [androidx.compose.ui.platform.LocalWindowInfo]. That one is refreshed once
 * per configuration change, and a refresh taken before the system has finished resizing the window
 * keeps the old orientation's size until the next change (#1867). Layout always runs at the
 * window's real size, so this cannot go stale.
 */
val LocalWindowDpSize = staticCompositionLocalOf { DpSize.Unspecified }

/**
 * [LocalWindowDpSize], or the window's own reading where no app root has measured it, as in a
 * component test or a preview.
 */
@Composable
fun flowWindowDpSize(): DpSize {
    val measured = LocalWindowDpSize.current
    if (measured.isSpecified) return measured
    return with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize
            .toSize()
            .toDpSize()
    }
}

/**
 * The window's size class, computed once at the root from [LocalWindowDpSize]. Readers below the
 * root take it from here instead of re-deriving it from [android.content.res.Configuration].
 */
val LocalWindowSizeClass = staticCompositionLocalOf { WindowSizeClass(0, 0) }

/**
 * Whether that same container is wider than it is tall.
 *
 * A size class cannot answer this: its width and height are bucket floors, so a 900x1400 portrait
 * tablet and a 1400x900 landscape one both report expanded by expanded. Layouts that put two
 * columns side by side need the real proportion, not the bucket.
 */
val LocalWindowIsLandscape = staticCompositionLocalOf { false }

@Composable
fun ProvideWindowSizeClass(content: @Composable () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val windowSize = DpSize(maxWidth, maxHeight)
        val windowSizeClass =
            remember(windowSize) {
                WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(windowSize.width.value, windowSize.height.value)
            }
        CompositionLocalProvider(
            LocalWindowDpSize provides windowSize,
            LocalWindowSizeClass provides windowSizeClass,
            LocalWindowIsLandscape provides (windowSize.width > windowSize.height),
            content = content,
        )
    }
}

val WindowSizeClass.isMediumWidth: Boolean
    get() = isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

val WindowSizeClass.isExpandedWidth: Boolean
    get() = isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

val WindowSizeClass.isLargeWidth: Boolean
    get() = isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND)

val WindowSizeClass.isMediumHeight: Boolean
    get() = isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
