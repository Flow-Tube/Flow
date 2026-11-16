package io.github.aedev.flow.data.model

import io.github.aedev.flow.data.local.SponsorBlockAction

/**
 * The SponsorBlock categories Flow fetches, configures and submits, in the order settings list them.
 * `chapter` is left out: chapters are not something to act on.
 */
object SponsorBlockCategories {
    const val SPONSOR = "sponsor"
    const val INTRO = "intro"
    const val OUTRO = "outro"
    const val SELF_PROMO = "selfpromo"
    const val INTERACTION = "interaction"
    const val MUSIC_OFF_TOPIC = "music_offtopic"
    const val FILLER = "filler"
    const val PREVIEW = "preview"
    const val EXCLUSIVE_ACCESS = "exclusive_access"
    const val HOOK = "hook"
    const val HIGHLIGHT = "poi_highlight"

    val all: List<String> =
        listOf(SPONSOR, INTRO, OUTRO, SELF_PROMO, INTERACTION, HIGHLIGHT, MUSIC_OFF_TOPIC, FILLER, PREVIEW, HOOK, EXCLUSIVE_ACCESS)

    /**
     * The server only accepts [EXCLUSIVE_ACCESS] as a whole-video label, never as a time range, and
     * [HIGHLIGHT] is a single point, which the submit dialog cannot make.
     */
    val submittable: List<String> = all - EXCLUSIVE_ACCESS - HIGHLIGHT

    /**
     * `full` is what returns whole-video labels, which arrive as a zero-length `[0, 0]` segment, and
     * `poi` returns highlights, whose start and end are the same moment.
     */
    val actionTypes: List<String> = listOf("skip", "mute", "full", "poi")

    /** A highlight is a point, so it cannot be skipped or muted. */
    fun actionsFor(category: String): List<SponsorBlockAction> =
        if (category == HIGHLIGHT) {
            listOf(SponsorBlockAction.IGNORE, SponsorBlockAction.SKIP_BUTTON, SponsorBlockAction.DISABLED)
        } else {
            SponsorBlockAction.entries
        }

    /**
     * Filler, previews and hooks are often wanted, so they show on the seek bar without being skipped,
     * and a highlight is there to be seen rather than to jump to unasked.
     */
    fun defaultAction(category: String): SponsorBlockAction =
        when (category) {
            FILLER, PREVIEW, HOOK, HIGHLIGHT -> SponsorBlockAction.IGNORE
            else -> SponsorBlockAction.SKIP
        }
}
