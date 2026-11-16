package io.github.aedev.flow.data.paging

import io.github.aedev.flow.data.local.WatchState
import io.github.aedev.flow.data.local.WatchedThreshold
import io.github.aedev.flow.data.local.dao.WatchProgress
import io.github.aedev.flow.data.local.isWatched
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.shorts.isShortComplete

/** Answers which of a page's videos the user has watched, from one history read for just those ids. */
fun interface WatchedVideoLookup {
    suspend operator fun invoke(videos: List<Video>): Set<String>
}

/** Every video a page shows, loose or inside a strip, so one lookup covers the whole page. */
internal fun List<SearchResultItem>.videos(): List<Video> =
    flatMap { item ->
        when (item) {
            is SearchResultItem.VideoResult -> listOf(item.video)
            is SearchResultItem.ShelfResult -> item.videos
            is SearchResultItem.ChannelResult, is SearchResultItem.PlaylistResult -> emptyList()
        }
    }

/**
 * Keeps what [state] asks for. Channels, playlists and posts have no watched state: they stay under
 * Unwatched, where they are still new to the viewer, and go under Watched, which is a list of videos.
 */
internal fun List<SearchResultItem>.withWatchState(
    state: WatchState,
    watchedIds: Set<String>,
): List<SearchResultItem> {
    if (state == WatchState.ANY) return this
    val keepWatched = state == WatchState.WATCHED

    fun Video.kept() = (id in watchedIds) == keepWatched

    return mapNotNull { item ->
        when (item) {
            is SearchResultItem.VideoResult -> {
                item.takeIf { it.video.kept() }
            }

            is SearchResultItem.ChannelResult, is SearchResultItem.PlaylistResult -> {
                item.takeUnless { keepWatched }
            }

            is SearchResultItem.ShelfResult -> {
                val videos = item.videos.filter { it.kept() }
                when {
                    videos.isNotEmpty() -> item.copy(videos = videos)
                    item.posts.isNotEmpty() && !keepWatched -> item
                    else -> null
                }
            }
        }
    }
}

/** Shorts count as watched on the Shorts line (#979), videos on the user's [threshold] and, with [countImported], when imported. */
internal fun watchedVideoIds(
    videos: List<Video>,
    progress: List<WatchProgress>,
    threshold: WatchedThreshold,
    countImported: Boolean = false,
): Set<String> {
    val byId = progress.associateBy { it.videoId }
    return videos
        .filter { video ->
            val entry = byId[video.id] ?: return@filter false
            if (video.isShort) {
                isShortComplete(entry.position, entry.duration)
            } else {
                threshold.isWatched(entry.position, entry.duration, countImported)
            }
        }.mapTo(HashSet()) { it.id }
}
