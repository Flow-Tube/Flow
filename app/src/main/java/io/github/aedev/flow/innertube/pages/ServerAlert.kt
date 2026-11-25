package io.github.aedev.flow.innertube.pages

import kotlinx.serialization.json.JsonElement

/**
 * YouTube refused the page and said why in its own words, such as "The playlist does not exist."
 * [alert] is blank when the refusal came with no text.
 */
class YouTubeAlertException(
    val alert: String,
) : Exception(alert)

/**
 * Throws [YouTubeAlertException] when the response is an ERROR alert and nothing else. A missing
 * playlist or channel answers 200 this way. INFO alerts ("Unavailable videos are hidden") arrive
 * beside real contents and are left alone.
 */
internal fun JsonElement.throwIfRefused() {
    val root = objectOrNull() ?: return
    if (CONTENT_KEYS.any(root::containsKey)) return
    val alert =
        root["alerts"].arrayOrNull()?.firstNotNullOfOrNull { entry ->
            entry
                .objectOrNull()
                ?.get("alertRenderer")
                .objectOrNull()
                ?.takeIf { it["type"].stringOrNull() == "ERROR" }
        } ?: return
    throw YouTubeAlertException(alert["text"].youtubeText()?.trim().orEmpty())
}

private val CONTENT_KEYS = listOf("contents", "onResponseReceivedActions", "continuationContents")
