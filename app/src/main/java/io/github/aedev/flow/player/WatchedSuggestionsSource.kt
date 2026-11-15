package io.github.aedev.flow.player

import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.dao.WatchHistoryDao
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.paging.watchedVideoIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Which suggested videos the viewer has watched, when they asked for those to be hidden. */
fun interface WatchedSuggestionsSource {
    suspend fun watchedAmong(videos: List<Video>): Set<String>
}

/** Reads the history for just the suggested ids, on the same watched rules as Search. */
@Singleton
class WatchedSuggestions
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
        private val watchHistoryDao: WatchHistoryDao,
    ) : WatchedSuggestionsSource {
        override suspend fun watchedAmong(videos: List<Video>): Set<String> {
            if (videos.isEmpty() || !preferences.hideWatchedSuggestions.first()) return emptySet()
            return try {
                val progress = watchHistoryDao.getProgressFor(videos.map { it.id }.distinct())
                watchedVideoIds(videos, progress, preferences.watchedThreshold.first(), preferences.countImportedAsWatched.first())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptySet()
            }
        }
    }
