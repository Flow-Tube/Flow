package io.github.aedev.flow.data.model

/**
 * One page of a watch page's related lane, in YouTube's order. [upNextVideoId] is the video YouTube
 * would play next, which need not be the first one listed; [continuation] asks for the next page.
 */
data class WatchRelatedPage(
    val videos: List<Video>,
    val upNextVideoId: String? = null,
    val continuation: String? = null,
)
