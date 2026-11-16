package io.github.aedev.flow.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Default segment colours for SponsorBlock categories.
 *
 * These are fixed convention colours rather than theme-scheme roles — they match what SponsorBlock
 * uses everywhere else, so a segment stays recognisable to users who already know the palette. They
 * live in the theme layer rather than inline at the draw site because the user can override any of
 * them per category in settings, and because there is more than one place that paints a segment.
 */
val SponsorBlockSponsor = Color(0xFF00D100)
val SponsorBlockSelfPromo = Color(0xFFFFFF00)
val SponsorBlockInteraction = Color(0xFFFF00FF)
val SponsorBlockIntroOutro = Color(0xFF00FFFF)
val SponsorBlockMusicOffTopic = Color(0xFFFF8000)
val SponsorBlockFiller = Color(0xFF7300FF)
val SponsorBlockPreview = Color(0xFF008FD6)
val SponsorBlockHook = Color(0xFF395699)
val SponsorBlockHighlight = Color(0xFFFF1684)

/** Opacity segments are drawn at so the progress track stays readable underneath them. */
const val SPONSOR_BLOCK_SEGMENT_ALPHA = 0.78f

/**
 * Default colour for [category], used when the user has not chosen a custom one.
 * Unknown categories fall back to the sponsor colour, matching the previous behaviour.
 */
fun defaultSponsorBlockColor(category: String): Color =
    when (category) {
        "sponsor" -> SponsorBlockSponsor
        "selfpromo" -> SponsorBlockSelfPromo
        "interaction" -> SponsorBlockInteraction
        "intro", "outro" -> SponsorBlockIntroOutro
        "music_offtopic" -> SponsorBlockMusicOffTopic
        "filler" -> SponsorBlockFiller
        "preview" -> SponsorBlockPreview
        "hook" -> SponsorBlockHook
        "poi_highlight" -> SponsorBlockHighlight
        else -> SponsorBlockSponsor
    }

/** The colours offered when the user picks their own colour for a segment category. */
val SponsorBlockSegmentPresets: List<Color> =
    listOf(
        Color(0xFF00D400),
        Color(0xFFFFFF00),
        Color(0xFF0000FF),
        Color(0xFFFF0000),
        Color(0xFFFF7700),
        Color(0xFFFF69B4),
        Color(0xFF7700FF),
        Color(0xFF00FFFF),
        Color(0xFFFFFFFF),
        Color(0xFF008080),
        Color(0xFF3F51B5),
        Color(0xFFFFC107),
        Color(0xFFCDDC39),
        Color(0xFF673AB7),
        Color(0xFFFF5722),
        Color(0xFFE91E63),
        Color(0xFF006400),
        Color(0xFF8B4513),
        Color(0xFF808080),
        Color(0xFFC0C0C0),
        Color(0xFFFFD700),
        Color(0xFF40E0D0),
        Color(0xFF4B0082),
    )
