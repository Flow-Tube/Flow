package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.annotation.StringRes
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.LYRICS_ALIGN_CENTER
import io.github.aedev.flow.data.local.LYRICS_ALIGN_LEFT
import io.github.aedev.flow.data.local.LYRICS_ALIGN_RIGHT
import io.github.aedev.flow.data.local.LyricsAnimationStyle
import io.github.aedev.flow.data.local.LyricsBackgroundStyle
import io.github.aedev.flow.data.local.LyricsGlow

@StringRes
internal fun lyricsAnimationLabel(style: LyricsAnimationStyle): Int =
    when (style) {
        LyricsAnimationStyle.CADENCE -> R.string.lyrics_animation_cadence
        LyricsAnimationStyle.CLASSIC -> R.string.lyrics_animation_classic
        LyricsAnimationStyle.CALM -> R.string.lyrics_animation_calm
    }

@StringRes
internal fun lyricsAnimationDetail(style: LyricsAnimationStyle): Int =
    when (style) {
        LyricsAnimationStyle.CADENCE -> R.string.lyrics_animation_cadence_detail
        LyricsAnimationStyle.CLASSIC -> R.string.lyrics_animation_classic_detail
        LyricsAnimationStyle.CALM -> R.string.lyrics_animation_calm_detail
    }

@StringRes
internal fun lyricsGlowLabel(glow: LyricsGlow): Int =
    when (glow) {
        LyricsGlow.OFF -> R.string.lyrics_glow_off
        LyricsGlow.SOFT -> R.string.lyrics_glow_soft
        LyricsGlow.NORMAL -> R.string.lyrics_glow_normal
        LyricsGlow.BRIGHT -> R.string.lyrics_glow_bright
    }

@StringRes
internal fun lyricsBackgroundLabel(style: LyricsBackgroundStyle): Int =
    when (style) {
        LyricsBackgroundStyle.SOLID -> R.string.lyrics_background_solid
        LyricsBackgroundStyle.COLORFUL -> R.string.lyrics_background_colorful
        LyricsBackgroundStyle.MOVING -> R.string.lyrics_background_moving
    }

@StringRes
internal fun lyricsBackgroundDetail(style: LyricsBackgroundStyle): Int =
    when (style) {
        LyricsBackgroundStyle.SOLID -> R.string.lyrics_background_solid_detail
        LyricsBackgroundStyle.COLORFUL -> R.string.lyrics_background_colorful_detail
        LyricsBackgroundStyle.MOVING -> R.string.lyrics_background_moving_detail
    }

/** The stored lyric alignments, in the order the settings offer them. */
internal val LyricsAlignments = listOf(LYRICS_ALIGN_LEFT, LYRICS_ALIGN_CENTER, LYRICS_ALIGN_RIGHT)

@StringRes
internal fun lyricsAlignmentLabel(align: String): Int =
    when (align) {
        LYRICS_ALIGN_LEFT -> R.string.lyrics_align_short_left
        LYRICS_ALIGN_RIGHT -> R.string.lyrics_align_short_right
        else -> R.string.lyrics_align_short_center
    }
