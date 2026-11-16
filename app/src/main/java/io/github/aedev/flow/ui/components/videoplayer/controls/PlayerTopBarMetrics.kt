package io.github.aedev.flow.ui.components.videoplayer.controls

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val PlayerTopBarActionButtonSize = 40.dp
internal val PlayerTopBarRowMinHeight = 44.dp

internal fun playerTopBarVerticalPadding(isFullscreen: Boolean): Dp = if (isFullscreen) 8.dp else 4.dp

internal fun playerTopBarTopPadding(
    isFullscreen: Boolean,
    isPortraitFullscreen: Boolean,
    cutoutTop: Dp,
): Dp =
    when {
        isFullscreen && isPortraitFullscreen -> cutoutTop.coerceAtLeast(16.dp)
        isFullscreen -> cutoutTop
        else -> 0.dp
    }

internal fun playerTopBarActionRowBottom(
    isFullscreen: Boolean,
    isPortraitFullscreen: Boolean,
    cutoutTop: Dp,
): Dp {
    val row = maxOf(PlayerTopBarRowMinHeight, PlayerTopBarActionButtonSize + playerTopBarVerticalPadding(isFullscreen) * 2)
    return playerTopBarTopPadding(isFullscreen, isPortraitFullscreen, cutoutTop) + row
}

/**
 * For a layer that doesn't know the fullscreen layout: in fullscreen it takes the portrait inset,
 * which is the only fullscreen layout where the top bar is narrow enough to meet the layer.
 */
@Composable
internal fun playerTopBarClearance(isFullscreen: Boolean): Dp =
    playerTopBarActionRowBottom(
        isFullscreen = isFullscreen,
        isPortraitFullscreen = true,
        cutoutTop = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding(),
    )
