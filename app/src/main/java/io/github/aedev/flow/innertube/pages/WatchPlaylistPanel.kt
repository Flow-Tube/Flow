package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.pages.renderer.largestImageUrl
import io.github.aedev.flow.innertube.pages.renderer.parseDurationText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The part of a playlist the watch page sends with a video played from it: about a hundred videos
 * around [videos]`[currentIndex]`, the playlist's full [total], and the token for the videos after.
 */
data class WatchPlaylistWindow(
    val playlistId: String?,
    val title: String?,
    val videos: List<Video>,
    val currentIndex: Int,
    val total: Int?,
    val continuation: String?,
)

/** The playlist window in a WEB `/next` response asked with a `playlistId`, or null without one. */
internal fun JsonElement.toWatchPlaylistWindow(): WatchPlaylistWindow? {
    val panel =
        objectOrNull()
            ?.get("contents")
            .objectOrNull()
            ?.get("twoColumnWatchNextResults")
            .objectOrNull()
            ?.get("playlist")
            .objectOrNull()
            ?.get("playlist")
            .objectOrNull() ?: return null
    val renderers = panel.panelVideoRenderers()
    val videos = renderers.mapNotNull { it.toPanelVideo() }
    if (videos.isEmpty()) return null
    val selectedId = renderers.firstOrNull { it["selected"].booleanOrNull() == true }?.get("videoId").stringOrNull()
    val currentIndex =
        videos.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
            ?: panel["localCurrentIndex"].stringOrNull()?.toIntOrNull()?.coerceIn(0, videos.lastIndex)
            ?: 0
    return WatchPlaylistWindow(
        playlistId = panel["playlistId"].stringOrNull(),
        title = panel["title"].youtubeText()?.takeIf(String::isNotBlank),
        videos = videos,
        currentIndex = currentIndex,
        total = panel["totalVideos"].stringOrNull()?.toIntOrNull(),
        continuation = panel.nextContinuation(),
    )
}

/** The videos of a `playlistPanelContinuation` and the token after them. */
internal fun JsonElement.toWatchPlaylistContinuation(): Pair<List<Video>, String?>? {
    val panel =
        objectOrNull()
            ?.get("continuationContents")
            .objectOrNull()
            ?.get("playlistPanelContinuation")
            .objectOrNull() ?: return null
    return panel.panelVideoRenderers().mapNotNull { it.toPanelVideo() } to panel.nextContinuation()
}

private fun JsonObject.panelVideoRenderers(): List<JsonObject> =
    this["contents"].arrayOrNull().orEmpty().mapNotNull { it.objectOrNull()?.get("playlistPanelVideoRenderer").objectOrNull() }

private fun JsonObject.nextContinuation(): String? =
    this["continuations"].arrayOrNull().orEmpty().firstNotNullOfOrNull { item ->
        item
            .objectOrNull()
            ?.get("nextContinuationData")
            .objectOrNull()
            ?.get("continuation")
            .stringOrNull()
            ?.takeIf(String::isNotBlank)
    }

// Private and deleted videos come without a length or an owner and can't be played.
private fun JsonObject.toPanelVideo(): Video? {
    val id = this["videoId"].stringOrNull()?.takeIf(String::isNotBlank) ?: return null
    if (containsKey("unplayableText")) return null
    val byline = this["shortBylineText"].objectOrNull() ?: return null
    val ownerRun = byline["runs"].arrayOrNull()?.firstOrNull().objectOrNull()
    return Video(
        id = id,
        title = this["title"].youtubeText().orEmpty(),
        channelName = byline.youtubeText().orEmpty(),
        channelId =
            ownerRun
                ?.get("navigationEndpoint")
                .objectOrNull()
                ?.get("browseEndpoint")
                .objectOrNull()
                ?.get("browseId")
                .stringOrNull()
                .orEmpty(),
        thumbnailUrl = this["thumbnail"].largestImageUrl().orEmpty(),
        duration = parseDurationText(this["lengthText"].youtubeText()) ?: 0,
        viewCount = 0L,
        uploadDate = "",
    )
}
