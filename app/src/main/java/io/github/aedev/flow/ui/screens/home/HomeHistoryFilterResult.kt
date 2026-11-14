package io.github.aedev.flow.ui.screens.home

import io.github.aedev.flow.data.local.VideoHistoryEntry
import io.github.aedev.flow.data.local.WatchedThreshold
import io.github.aedev.flow.data.local.isWatched
import io.github.aedev.flow.data.shorts.isShortComplete

internal data class HomeHistoryFilterResult(
    val watchedVideoIds: Set<String>,
    val continueWatchingVideos: List<VideoHistoryEntry>,
    /** Filters the Shorts shelf, under its own setting rather than the videos one. */
    val watchedShortIds: Set<String>,
)

internal fun filterHomeHistory(
    history: List<VideoHistoryEntry>,
    hideWatchedVideos: Boolean,
    watchedThreshold: WatchedThreshold,
    continueWatchingEnabled: Boolean,
    hideWatchedShorts: Boolean,
    countImported: Boolean = false,
): HomeHistoryFilterResult {
    val watchedIds by lazy(LazyThreadSafetyMode.NONE) {
        history
            .asSequence()
            .filter { watchedThreshold.isWatched(it.position, it.duration, countImported) }
            .mapTo(HashSet()) { it.videoId }
    }
    val watchedVideoIds = if (hideWatchedVideos) watchedIds else emptySet()

    val continueWatchingVideos =
        if (continueWatchingEnabled) {
            history
                .asSequence()
                .filter { !it.isShort && it.progressPercentage >= 3f }
                .filter { entry ->
                    if (hideWatchedVideos) {
                        entry.videoId !in watchedVideoIds
                    } else {
                        entry.progressPercentage <= 90f
                    }
                }.sortedByDescending { it.timestamp }
                .take(20)
                .toList()
        } else {
            emptyList()
        }

    return HomeHistoryFilterResult(
        watchedVideoIds = watchedVideoIds,
        continueWatchingVideos = continueWatchingVideos,
        watchedShortIds =
            if (hideWatchedShorts) {
                history.filter { isShortComplete(it.position, it.duration) }.mapTo(HashSet()) { it.videoId }
            } else {
                emptySet()
            },
    )
}
