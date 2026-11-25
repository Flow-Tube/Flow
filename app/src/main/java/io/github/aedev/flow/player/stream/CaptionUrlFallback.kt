package io.github.aedev.flow.player.stream

import io.github.aedev.flow.player.error.StreamDenialClassifier

/**
 * What to fetch when YouTube refuses a caption track outright (#2375).
 *
 * Its newer auto captions carry `variant=` (for example `variant=gemini`), and some networks are
 * refused them while the classic auto track of the same video still loads. `variant` sits outside
 * the URL's signed parameter list, so dropping it keeps the signature valid.
 */
object CaptionUrlFallback {
    private const val VARIANT_PARAM = "variant"
    private const val KIND_PARAM = "kind"
    private const val ASR_KIND = "asr"
    private const val HTTP_TOO_MANY_REQUESTS = 429

    /** The classic auto track for a native auto track with a variant, or null when there is none to try. */
    fun alternateFor(url: String): String? {
        if (StreamDenialClassifier.queryParam(url, KIND_PARAM) != ASR_KIND) return null
        if (!StreamDenialClassifier.queryParam(url, CaptionTrackResolver.TRANSLATED_PARAM).isNullOrEmpty()) return null
        if (StreamDenialClassifier.queryParam(url, VARIANT_PARAM) == null) return null
        val separator = url.indexOf('?')
        val kept =
            url
                .substring(separator + 1)
                .split('&')
                .filterNot { it == VARIANT_PARAM || it.startsWith("$VARIANT_PARAM=") }
        return url.substring(0, separator) + "?" + kept.joinToString("&")
    }

    /** A status that retrying the same request cannot change. */
    fun isRefusal(status: Int?): Boolean = status != null && status in 400..499 && status != HTTP_TOO_MANY_REQUESTS

    /** A refusal that means the track is not offered to this viewer at all. */
    fun isUnavailable(status: Int?): Boolean = status == 403 || status == 404 || status == 410
}
