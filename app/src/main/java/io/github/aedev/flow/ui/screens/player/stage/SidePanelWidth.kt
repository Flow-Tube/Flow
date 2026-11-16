package io.github.aedev.flow.ui.screens.player.stage

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.local.FullscreenSidePanelWidth

private val DefaultPanelCap = 420.dp
private val PanelFloor = 240.dp
private const val DEFAULT_FRACTION = 0.42f
private const val FLOOR_MAX_FRACTION = 0.5f

/**
 * The fullscreen side panel's width. Each preset caps at the default's 420 dp scaled by its share,
 * and never drops below 240 dp, or half the window when that is smaller.
 */
internal fun sidePanelWidth(
    maxWidth: Dp,
    preset: FullscreenSidePanelWidth,
): Dp {
    val cap = DefaultPanelCap * (preset.fraction / DEFAULT_FRACTION)
    val floor = minOf(PanelFloor, maxWidth * FLOOR_MAX_FRACTION)
    return maxOf(minOf(maxWidth * preset.fraction, cap), floor)
}

/** The narrowest the video gets beside the panel; it never reaches under a floored panel. */
internal fun sidePanelMinPlayerWidth(
    maxWidth: Dp,
    preset: FullscreenSidePanelWidth,
): Dp = minOf(maxWidth * (1f - preset.fraction), maxWidth - sidePanelWidth(maxWidth, preset))
