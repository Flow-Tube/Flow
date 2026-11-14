package io.github.aedev.flow.innertube.pages.channel

internal const val CHANNEL_SEARCH_FALLBACK_LANGUAGE = "en"

/**
 * YouTube answers some channel searches with nothing in one language and finds them in English
 * (#2057: "xlov" on a channel has no videos with `hl=es`, 28 with `hl=en`, youtube.com included).
 * So an empty answer in any other language is asked once more in English.
 */
internal fun shouldSearchChannelInEnglish(
    videoCount: Int,
    hl: String,
): Boolean = videoCount == 0 && hl.substringBefore('-').lowercase() != CHANNEL_SEARCH_FALLBACK_LANGUAGE
