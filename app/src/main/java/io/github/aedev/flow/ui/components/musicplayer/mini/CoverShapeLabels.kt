package io.github.aedev.flow.ui.components.musicplayer.mini

import androidx.annotation.StringRes
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.CoverShape

@StringRes
internal fun coverShapeLabel(shape: CoverShape): Int =
    when (shape) {
        CoverShape.CIRCLE -> R.string.cover_shape_circle
        CoverShape.SQUARE -> R.string.cover_shape_square
        CoverShape.SLANTED -> R.string.cover_shape_slanted
        CoverShape.ARCH -> R.string.cover_shape_arch
        CoverShape.FAN -> R.string.cover_shape_fan
        CoverShape.ARROW -> R.string.cover_shape_arrow
        CoverShape.SEMI_CIRCLE -> R.string.cover_shape_semi_circle
        CoverShape.OVAL -> R.string.cover_shape_oval
        CoverShape.PILL -> R.string.cover_shape_pill
        CoverShape.TRIANGLE -> R.string.cover_shape_triangle
        CoverShape.DIAMOND -> R.string.cover_shape_diamond
        CoverShape.CLAM_SHELL -> R.string.cover_shape_clam_shell
        CoverShape.PENTAGON -> R.string.cover_shape_pentagon
        CoverShape.GEM -> R.string.cover_shape_gem
        CoverShape.SUNNY -> R.string.cover_shape_sunny
        CoverShape.VERY_SUNNY -> R.string.cover_shape_very_sunny
        CoverShape.COOKIE_4 -> R.string.cover_shape_cookie_4
        CoverShape.COOKIE_6 -> R.string.cover_shape_cookie_6
        CoverShape.COOKIE_7 -> R.string.cover_shape_cookie_7
        CoverShape.COOKIE_9 -> R.string.cover_shape_cookie_9
        CoverShape.COOKIE_12 -> R.string.cover_shape_cookie_12
        CoverShape.GHOSTISH -> R.string.cover_shape_ghostish
        CoverShape.CLOVER_4 -> R.string.cover_shape_clover_4
        CoverShape.CLOVER_8 -> R.string.cover_shape_clover_8
        CoverShape.BURST -> R.string.cover_shape_burst
        CoverShape.SOFT_BURST -> R.string.cover_shape_soft_burst
        CoverShape.BOOM -> R.string.cover_shape_boom
        CoverShape.SOFT_BOOM -> R.string.cover_shape_soft_boom
        CoverShape.FLOWER -> R.string.cover_shape_flower
        CoverShape.PUFFY -> R.string.cover_shape_puffy
        CoverShape.PUFFY_DIAMOND -> R.string.cover_shape_puffy_diamond
        CoverShape.PIXEL_CIRCLE -> R.string.cover_shape_pixel_circle
        CoverShape.PIXEL_TRIANGLE -> R.string.cover_shape_pixel_triangle
        CoverShape.BUN -> R.string.cover_shape_bun
        CoverShape.HEART -> R.string.cover_shape_heart
    }
