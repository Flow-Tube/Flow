package io.github.aedev.flow.data.paging

import io.github.aedev.flow.data.local.ContentType
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.pages.YouTubeCountParser
import io.github.aedev.flow.innertube.pages.search.SearchResultsPage
import io.github.aedev.flow.utils.ThumbnailUrlResolver

/**
 * Video results from YouTube Music, for a search YouTube answered with its age check. YouTube Music
 * runs no such check for a signed-out viewer, and its Videos filter returns the same uploads.
 */
fun interface MusicVideoSearch {
    suspend operator fun invoke(query: String): List<Video>
}

internal val DefaultMusicVideoSearch =
    MusicVideoSearch { query ->
        YouTube
            .search(query, YouTube.SearchFilter.FILTER_VIDEO)
            .getOrThrow()
            .items
            .filterIsInstance<SongItem>()
            .mapNotNull { it.toSearchVideo(YouTube.locale.hl) }
    }

/**
 * Only a first page that is the age check and nothing else, and only where video results belong:
 * the Shorts, channel and playlist filters would get songs they never asked for.
 */
internal fun SearchResultsPage.needsMusicFallback(contentType: ContentType): Boolean =
    ageGated &&
        sections.isEmpty() &&
        (contentType == ContentType.ALL || contentType == ContentType.VIDEOS)

/** A YouTube Music video row as a search result. It carries no upload date, so none is shown. */
internal fun SongItem.toSearchVideo(hl: String?): Video? {
    if (id.isBlank() || title.isBlank()) return null
    return Video(
        id = id,
        title = title,
        channelName = artists.joinToString(", ") { it.name },
        channelId = artists.firstNotNullOfOrNull { artist -> artist.id?.takeIf { it.startsWith(CHANNEL_ID_PREFIX) } }.orEmpty(),
        thumbnailUrl = ThumbnailUrlResolver.buildFallbackYoutubeThumbnail(id),
        duration = duration ?: 0,
        viewCount = YouTubeCountParser.parse(viewCountText, hl) ?: 0L,
        uploadDate = "",
        timestamp = 0L,
    )
}

private const val CHANNEL_ID_PREFIX = "UC"
