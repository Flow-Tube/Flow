package io.github.aedev.flow.utils

import io.github.aedev.flow.R

/** The display name for a SponsorBlock category id, or null for one this build does not name. */
fun sponsorCategoryLabelRes(category: String): Int? =
    when (category) {
        "sponsor" -> R.string.sb_category_sponsor
        "selfpromo" -> R.string.sb_category_selfpromo
        "interaction" -> R.string.sb_category_interaction
        "intro" -> R.string.sb_category_intro
        "outro" -> R.string.sb_category_outro
        "music_offtopic" -> R.string.sb_category_music_offtopic
        "filler" -> R.string.sb_category_filler
        "preview" -> R.string.sb_category_preview
        "exclusive_access" -> R.string.sb_category_exclusive_access
        "hook" -> R.string.sb_category_hook
        "poi_highlight" -> R.string.sb_category_highlight
        else -> null
    }
