package io.github.aedev.flow.player.state

/**
 * A subtitle track whose fetch ran out of retries.
 *
 * [isTranslated] separates the two outcomes worth distinguishing: a machine translation is refused
 * by Google's abuse interstitial often enough that the source track is still worth showing, whereas
 * an authored track failing means there is nothing left to fall back to.
 */
data class SubtitleLoadFailure(
    val index: Int,
    val label: String,
    val language: String?,
    val isTranslated: Boolean,
    val httpStatus: Int? = null,
)
