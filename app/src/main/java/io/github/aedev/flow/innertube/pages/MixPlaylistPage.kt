package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.pages.renderer.parseDurationText
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A mix is built around a video; `RDCLAK` ids are curated music playlists that only share the prefix. */
internal fun isMix(playlistId: String): Boolean = playlistId.startsWith("RD") && !playlistId.startsWith("RDCLAK")

/** The video a plain `RD<videoId>` mix grew from. Asking with it keeps that video first in the queue. */
internal fun mixSeedVideoId(playlistId: String): String? = playlistId.removePrefix("RD").takeIf { it.length == VIDEO_ID_LENGTH }

private const val VIDEO_ID_LENGTH = 11

/**
 * A mix from the WEB `next` response. The playlist browse refuses one ("This playlist type is
 * unviewable"), so its watch queue is the only list of it, and that queue has no next page.
 */
internal fun JsonElement.toMixPlaylistPage(): VideoPlaylistPage {
    val playlist =
        objectOrNull()
            ?.get("contents")
            .objectOrNull()
            ?.get("twoColumnWatchNextResults")
            .objectOrNull()
            ?.get("playlist")
            .objectOrNull()
            ?.get("playlist")
            .objectOrNull() ?: return VideoPlaylistPage()
    val videos =
        playlist["contents"]
            .arrayOrNull()
            .orEmpty()
            .mapNotNull {
                it
                    .objectOrNull()
                    ?.get("playlistPanelVideoRenderer")
                    .objectOrNull()
                    ?.toMixVideo()
            }.distinctBy { it.id }
    return VideoPlaylistPage(
        title = (playlist["title"].stringOrNull() ?: playlist["titleText"].youtubeText())?.takeIf(String::isNotBlank),
        ownerName = playlist["ownerName"].youtubeText()?.takeIf(String::isNotBlank),
        thumbnailUrl = videos.firstOrNull()?.thumbnailUrl,
        videos = videos,
    )
}

private fun JsonObject.toMixVideo(): Video? {
    if ("unplayableText" in this) return null
    val id = this["videoId"].stringOrNull()?.takeIf(String::isNotBlank) ?: return null
    val channel =
        this["shortBylineText"]
            .objectOrNull()
            ?.get("runs")
            .arrayOrNull()
            ?.firstOrNull()
            .objectOrNull()
    return Video(
        id = id,
        title = this["title"].youtubeText().orEmpty(),
        channelName = channel?.get("text").stringOrNull().orEmpty(),
        channelId =
            channel
                ?.get("navigationEndpoint")
                .objectOrNull()
                ?.get("browseEndpoint")
                .objectOrNull()
                ?.get("browseId")
                .stringOrNull()
                .orEmpty(),
        thumbnailUrl = ThumbnailUrlResolver.normalizeVideoThumbnail(id, null),
        duration = parseDurationText(this["lengthText"].youtubeText()) ?: 0,
        viewCount = 0L,
        uploadDate = "",
        timestamp = 0L,
    )
}
