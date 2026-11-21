package io.github.aedev.flow.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Laid over the status bar on pages that scroll content under it: the page shows through, and the
 * status bar icons stay readable. A light page needs more cover for its dark icons than a dark one.
 */
val ColorScheme.statusBarScrim: Color
    get() = background.copy(alpha = if (background.luminance() > HALF_LUMINANCE) LIGHT_SCRIM_ALPHA else DARK_SCRIM_ALPHA)

private const val HALF_LUMINANCE = 0.5f
private const val LIGHT_SCRIM_ALPHA = 0.78f
private const val DARK_SCRIM_ALPHA = 0.62f
