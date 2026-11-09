package io.github.aedev.flow.ui.screens.settings.appearance.player

import androidx.annotation.StringRes
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.FullscreenSidePanelWidth
import io.github.aedev.flow.data.local.GestureOverlayStyle
import io.github.aedev.flow.data.local.MusicPlayerBackgroundStyle
import io.github.aedev.flow.data.local.SeekbarPaddingMode
import io.github.aedev.flow.data.local.ShortsPlayerUiMode
import io.github.aedev.flow.data.local.SliderStyle

/**
 * Enum names predate the current slider drawings: SQUIGGLY draws the wave and EXPRESSIVE_WAVY the
 * squiggle, and stored preferences hold the old names, so the labels map across on purpose.
 */
@StringRes
internal fun sliderStyleLabel(style: SliderStyle): Int =
    when (style) {
        SliderStyle.DEFAULT -> R.string.style_default
        SliderStyle.THICK -> R.string.style_thick
        SliderStyle.COMPACT -> R.string.style_compact
        SliderStyle.SQUIGGLY -> R.string.style_wavy
        SliderStyle.EXPRESSIVE_WAVY -> R.string.style_squiggly
        SliderStyle.SLIM -> R.string.style_slim
    }

@StringRes
internal fun musicBackgroundLabel(style: MusicPlayerBackgroundStyle): Int =
    when (style) {
        MusicPlayerBackgroundStyle.BLUR_GRADIENT -> R.string.player_background_style_blur_gradient
        MusicPlayerBackgroundStyle.BLUR -> R.string.player_background_style_blur
        MusicPlayerBackgroundStyle.GRADIENT -> R.string.player_background_style_gradient
        MusicPlayerBackgroundStyle.IMMERSIVE -> R.string.player_background_style_immersive
        MusicPlayerBackgroundStyle.DEFAULT -> R.string.player_background_style_default
    }

@StringRes
internal fun shortsUiModeLabel(mode: ShortsPlayerUiMode): Int =
    when (mode) {
        ShortsPlayerUiMode.DEFAULT -> R.string.shorts_player_ui_default
        ShortsPlayerUiMode.SIMPLE -> R.string.shorts_player_ui_simple
        ShortsPlayerUiMode.IMPRESSIVE -> R.string.shorts_player_ui_impressive
    }

@StringRes
internal fun gestureOverlayLabel(style: GestureOverlayStyle): Int =
    when (style) {
        GestureOverlayStyle.CIRCULAR -> R.string.gesture_overlay_style_circular
        GestureOverlayStyle.VERTICAL -> R.string.gesture_overlay_style_vertical
        GestureOverlayStyle.HORIZONTAL -> R.string.gesture_overlay_style_horizontal
        GestureOverlayStyle.MINIMAL -> R.string.gesture_overlay_style_minimal
    }

@StringRes
internal fun seekbarModeLabel(mode: SeekbarPaddingMode): Int =
    when (mode) {
        SeekbarPaddingMode.FULL_WIDTH -> R.string.player_fullscreen_seekbar_width_full
        SeekbarPaddingMode.SPACED -> R.string.player_portrait_seekbar_width_spaced
        SeekbarPaddingMode.DEFAULT -> R.string.player_fullscreen_seekbar_width_default
        SeekbarPaddingMode.CUSTOM -> R.string.player_fullscreen_seekbar_width_custom
    }

@StringRes
internal fun sidePanelWidthLabel(width: FullscreenSidePanelWidth): Int =
    when (width) {
        FullscreenSidePanelWidth.NARROW -> R.string.player_side_panel_width_narrow
        FullscreenSidePanelWidth.SMALL -> R.string.player_side_panel_width_small
        FullscreenSidePanelWidth.DEFAULT -> R.string.player_side_panel_width_default
        FullscreenSidePanelWidth.WIDE -> R.string.player_side_panel_width_wide
    }

@StringRes
internal fun miniPlayerSizeLabel(size: MiniPlayerSize): Int =
    when (size) {
        MiniPlayerSize.SMALL -> R.string.mini_player_small
        MiniPlayerSize.NORMAL -> R.string.mini_player_normal
        MiniPlayerSize.LARGE -> R.string.mini_player_large
    }
