package io.github.aedev.flow.innertube.pages

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One `updated_metadata` answer: the counts and date the watch page shows, and when to ask again.
 *
 * The first answer on a live page also carries the merchandise shelf and an engagement panel,
 * together far larger than the rest; neither is read.
 */
data class LiveMetadataUpdate(
    val viewCount: Long? = null,
    /** False once the stream has ended; null when the answer carried no viewership at all. */
    val isLive: Boolean? = null,
    val dateText: String? = null,
    val likeCount: Long? = null,
    val continuation: String? = null,
    val timeoutMs: Long? = null,
)

internal fun JsonElement.toLiveMetadataUpdate(hl: String?): LiveMetadataUpdate {
    val root = objectOrNull() ?: return LiveMetadataUpdate()
    val actions = root["actions"].arrayOrNull().orEmpty().mapNotNull { it.objectOrNull() }
    val viewership =
        actions
            .firstNotNullOfOrNull { it["updateViewershipAction"].objectOrNull() }
            ?.get("viewCount")
            .objectOrNull()
            ?.get("videoViewCountRenderer")
            .objectOrNull()
    val timed =
        root["continuation"]
            .objectOrNull()
            ?.get("timedContinuationData")
            .objectOrNull()
    return LiveMetadataUpdate(
        viewCount = viewership?.viewCount(hl),
        isLive = viewership?.let { it["isLive"].booleanOrNull() ?: false },
        dateText =
            actions
                .firstNotNullOfOrNull { it["updateDateTextAction"].objectOrNull() }
                ?.get("dateText")
                .youtubeText()
                ?.takeIf { it.isNotBlank() },
        likeCount = root.likeCount(),
        continuation = timed?.get("continuation").stringOrNull()?.takeIf { it.isNotBlank() },
        timeoutMs = timed?.get("timeoutMs").stringOrNull()?.toLongOrNull(),
    )
}

// A stream can serve originalViewCount "0" beside a real count in the text, so the text is the fallback.
private fun JsonObject.viewCount(hl: String?): Long? =
    this["originalViewCount"].stringOrNull()?.toLongOrNull()?.takeIf { it > 0L }
        ?: parseYouTubeViewCount(this["viewCount"].youtubeText(), hl).takeIf { it > 0L }

private fun JsonObject.likeCount(): Long? =
    this["frameworkUpdates"]
        .objectOrNull()
        ?.get("entityBatchUpdate")
        .objectOrNull()
        ?.get("mutations")
        .arrayOrNull()
        .orEmpty()
        .firstNotNullOfOrNull { mutation ->
            mutation
                .objectOrNull()
                ?.get("payload")
                .objectOrNull()
                ?.get("likeCountEntity")
                .objectOrNull()
                ?.get("likeCountIfIndifferentNumber")
                .stringOrNull()
                ?.toLongOrNull()
        }
