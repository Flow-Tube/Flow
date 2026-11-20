package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.innertube.models.response.WatchMetadataResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One entry of a watch page's related lane, in the order YouTube lists it. */
sealed interface WatchRelatedEntry {
    data class Videos(
        val videos: List<WatchMetadataResponse.CompactVideo>,
    ) : WatchRelatedEntry
}

data class WatchRelatedResults(
    val entries: List<WatchRelatedEntry>,
)

/** The related lane of a full web watch response. */
fun JsonElement.watchRelatedResults(hl: String?): WatchRelatedResults =
    objectOrNull()
        ?.get("contents")
        .objectOrNull()
        ?.get("twoColumnWatchNextResults")
        .objectOrNull()
        ?.get("secondaryResults")
        .objectOrNull()
        ?.get("secondaryResults")
        .objectOrNull()
        ?.get("results")
        .arrayOrNull()
        .toWatchRelatedResults(hl)

/**
 * The video YouTube itself plays next: the autoplay set the countdown uses. A shuffle set can sit
 * beside it for playlists; only the plain one names the next related video.
 */
fun JsonElement.watchUpNextVideoId(): String? =
    objectOrNull()
        ?.get("contents")
        .objectOrNull()
        ?.get("twoColumnWatchNextResults")
        .objectOrNull()
        ?.get("autoplay")
        .objectOrNull()
        ?.get("autoplay")
        .objectOrNull()
        ?.get("sets")
        .arrayOrNull()
        ?.mapNotNull { it.objectOrNull() }
        ?.firstOrNull { it["mode"].stringOrNull().let { mode -> mode == null || mode == AUTOPLAY_MODE_NORMAL } }
        ?.get("autoplayVideo")
        .objectOrNull()
        ?.get("watchEndpoint")
        .objectOrNull()
        ?.get("videoId")
        .stringOrNull()
        ?.takeIf { it.isNotBlank() }

private fun JsonArray?.toWatchRelatedResults(hl: String?): WatchRelatedResults {
    val entries = mutableListOf<WatchRelatedEntry>()
    orEmpty().forEach { element -> collectEntries(element.objectOrNull() ?: return@forEach, hl, entries) }
    return WatchRelatedResults(entries)
}

private fun collectEntries(
    node: JsonObject,
    hl: String?,
    out: MutableList<WatchRelatedEntry>,
) {
    node["itemSectionRenderer"].objectOrNull()?.let { section ->
        section["contents"].arrayOrNull().orEmpty().forEach { child ->
            child.objectOrNull()?.let { collectEntries(it, hl, out) }
        }
        return
    }
    val videos =
        runCatching { relatedJson.decodeFromJsonElement(WatchMetadataResponse.SecondaryItem.serializer(), node) }
            .getOrNull()
            ?.videos(hl)
            .orEmpty()
    if (videos.isNotEmpty()) out += WatchRelatedEntry.Videos(videos)
}

private const val AUTOPLAY_MODE_NORMAL = "NORMAL"

private val relatedJson = Json { ignoreUnknownKeys = true }
