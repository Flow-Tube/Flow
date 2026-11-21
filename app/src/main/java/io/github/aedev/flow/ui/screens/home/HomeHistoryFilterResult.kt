package io.github.aedev.flow.ui.screens.home

import io.github.aedev.flow.data.local.VideoHistoryEntry
import io.github.aedev.flow.data.local.WatchedThreshold
import io.github.aedev.flow.data.local.dao.WatchProgress
import io.github.aedev.flow.data.local.isWatched
import io.github.aedev.flow.data.shorts.isShortComplete

internal data class HomeHistoryFilterResult(
    val watchedVideoIds: Set<String>,
    /** Newest first; the entries themselves are read for just these ids. */
    val continueWatchingIds: List<String>,
    /** Filters the Shorts shelf, under its own setting rather than the videos one. */
    val watchedShortIds: Set<String>,
)

internal fun filterHomeHistory(
    history: List<WatchProgress>,
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

    val continueWatchingIds =
        if (continueWatchingEnabled) {
            history
                .asSequence()
                .filter { !it.isShort && it.percentage() >= 3f }
                .filter { entry ->
                    if (hideWatchedVideos) {
                        entry.videoId !in watchedVideoIds
                    } else {
                        entry.percentage() <= 90f
                    }
                }.sortedByDescending { it.timestamp }
                .take(CONTINUE_WATCHING_LIMIT)
                .map { it.videoId }
                .toList()
        } else {
            emptyList()
        }

    return HomeHistoryFilterResult(
        watchedVideoIds = watchedVideoIds,
        continueWatchingIds = continueWatchingIds,
        watchedShortIds =
            if (hideWatchedShorts) {
                history.filter { isShortComplete(it.position, it.duration) }.mapTo(HashSet()) { it.videoId }
            } else {
                emptySet()
            },
    )
}

/** [entries] in the order of [ids], skipping any that history no longer holds. */
internal fun orderContinueWatching(
    ids: List<String>,
    entries: List<VideoHistoryEntry>,
): List<VideoHistoryEntry> {
    val byId = entries.associateBy { it.videoId }
    return ids.mapNotNull(byId::get)
}

private const val CONTINUE_WATCHING_LIMIT = 20

private fun WatchProgress.percentage(): Float = if (duration > 0) position.toFloat() / duration.toFloat() * 100f else 0f
