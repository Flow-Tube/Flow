package io.github.aedev.flow.innertube.pages.channel

import android.util.Log
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.parseYouTubeViewCount
import io.github.aedev.flow.utils.avatarImageIdentityKey

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

internal fun parseChannelSearchResponse(
    response: io.github.aedev.flow.innertube.models.response.ChannelSearchResponse,
    channelId: String,
    channelName: String,
    channelThumbnailUrl: String,
): YouTube.ChannelVideoSearchResult {
    val tabs =
        response.contents
            ?.twoColumnBrowseResultsRenderer
            ?.tabs
            .orEmpty()
    Log.d("ChannelSearch", "tabs=${tabs.size}")
    tabs.forEachIndexed { i, tab ->
        val url =
            tab.tabRenderer
                ?.endpoint
                ?.commandMetadata
                ?.webCommandMetadata
                ?.url
        Log.d(
            "ChannelSearch",
            "tab[$i]: url=$url, selected=${tab.tabRenderer?.selected}, " +
                "hasSection=${tab.tabRenderer?.content?.sectionListRenderer != null}, " +
                "hasRichGrid=${tab.tabRenderer?.content?.richGridRenderer != null}, " +
                "isExpandable=${tab.expandableTabRenderer != null}",
        )
    }

    val tabContent =
        tabs
            .firstOrNull {
                it.tabRenderer?.selected == true && it.tabRenderer.endpoint
                    ?.commandMetadata
                    ?.webCommandMetadata
                    ?.url
                    ?.contains("/search") == true
            }?.tabRenderer
            ?.content
            ?: tabs.firstOrNull { it.expandableTabRenderer?.content != null }?.expandableTabRenderer?.content
            ?: tabs.firstOrNull { it.tabRenderer?.selected == true }?.tabRenderer?.content

    Log.d(
        "ChannelSearch",
        "tabContent=${tabContent != null}, " +
            "hasSection=${tabContent?.sectionListRenderer != null}, " +
            "hasRichGrid=${tabContent?.richGridRenderer != null}",
    )

    val videos = mutableListOf<io.github.aedev.flow.data.model.Video>()
    var continuation: String? = null

    tabContent?.richGridRenderer?.contents?.forEach { richItem ->
        richItem.richItemRenderer
            ?.content
            ?.videoRenderer
            ?.let { parseVideoRenderer(it, channelId, channelName, channelThumbnailUrl) }
            ?.let { videos.add(it) }
        richItem.continuationItemRenderer
            ?.continuationEndpoint
            ?.continuationCommand
            ?.token
            ?.let { continuation = it }
    }

    if (videos.isEmpty()) {
        val sectionContents = tabContent?.sectionListRenderer?.contents.orEmpty()
        sectionContents
            .mapNotNull { it.itemSectionRenderer?.contents }
            .flatten()
            .mapNotNull { it.videoRenderer }
            .mapNotNull { parseVideoRenderer(it, channelId, channelName, channelThumbnailUrl) }
            .forEach { videos.add(it) }
        if (continuation == null) {
            continuation =
                sectionContents
                    .mapNotNull { it.continuationItemRenderer }
                    .firstOrNull()
                    ?.continuationEndpoint
                    ?.continuationCommand
                    ?.token
        }
    }

    Log.d("ChannelSearch", "videos=${videos.size}, hasContinuation=${continuation != null}")
    return YouTube.ChannelVideoSearchResult(videos = videos, continuation = continuation)
}

private fun parseVideoRenderer(
    r: io.github.aedev.flow.innertube.models.response.ChannelSearchResponse.VideoRenderer,
    channelId: String,
    channelName: String,
    channelThumbnailUrl: String,
): io.github.aedev.flow.data.model.Video? {
    val videoId = r.videoId ?: return null
    val title =
        r.title
            ?.runs
            ?.joinToString("") { it.text ?: "" }
            ?.takeIf { it.isNotBlank() } ?: return null
    val thumbnail =
        r.thumbnail
            ?.thumbnails
            ?.maxByOrNull { it.width ?: 0 }
            ?.url
            ?: "https://i.ytimg.com/vi/$videoId/hq720.jpg"
    val duration = parseLengthText(r.lengthText?.simpleText)
    val viewCount = parseYouTubeViewCount(r.viewCountText?.simpleText)
    val avatarUrls = r.channelAvatarUrls(channelThumbnailUrl)
    return io.github.aedev.flow.data.model.Video(
        id = videoId,
        title = title,
        channelName = channelName,
        channelId = channelId,
        thumbnailUrl = thumbnail,
        duration = duration,
        viewCount = viewCount,
        uploadDate = r.publishedTimeText?.simpleText ?: "",
        channelThumbnailUrl = avatarUrls.firstOrNull().orEmpty(),
        channelThumbnailUrls = avatarUrls,
    )
}

private fun io.github.aedev.flow.innertube.models.response.ChannelSearchResponse.VideoRenderer.channelAvatarUrls(
    fallback: String,
): List<String> {
    val supported = channelThumbnailSupportedRenderers
    val stackAvatars =
        listOfNotNull(
            avatarStackViewModel,
            supported?.avatarStackViewModel,
            supported?.channelThumbnailWithLinkRenderer?.avatarStack?.avatarStackViewModel,
        ).flatMap { stack ->
            stack.avatars.orEmpty().mapNotNull { avatar ->
                avatar.avatarViewModel
                    ?.image
                    ?.sources
                    ?.maxByOrNull { maxOf(it.width ?: 0, it.height ?: 0) }
                    ?.url
            }
        }
    val linkedAvatar =
        supported
            ?.channelThumbnailWithLinkRenderer
            ?.thumbnail
            ?.thumbnails
            ?.maxByOrNull { maxOf(it.width ?: 0, it.height ?: 0) }
            ?.url

    return (stackAvatars + linkedAvatar + fallback)
        .filter { !it.isNullOrBlank() }
        .distinctBy { it.avatarImageIdentityKey() }
        .take(2)
        .filterNotNull()
}

internal fun parseLengthText(text: String?): Int {
    if (text.isNullOrBlank()) return 0
    val parts = text.split(":").map { it.trim().toIntOrNull() ?: 0 }
    return when (parts.size) {
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        2 -> parts[0] * 60 + parts[1]
        else -> 0
    }
}
