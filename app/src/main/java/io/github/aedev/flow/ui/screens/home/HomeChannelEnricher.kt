package io.github.aedev.flow.ui.screens.home

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.YouTubeRepository
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

internal class HomeChannelEnricher(
    private val repository: YouTubeRepository,
    session: HomeFeedSession,
) {
    private val uiState = session.uiState
    private val channelsAskedAbout = ConcurrentHashMap.newKeySet<String>()

    suspend fun enrichVisibleChannelMetadata(videos: List<Video>): List<Video>? {
        val enriched = repository.enrichMissingChannelMetadata(videos)
        if (enriched == videos) return null

        val originalById = videos.associateBy { it.id }
        val updates =
            enriched
                .filter { enrichedVideo -> originalById[enrichedVideo.id] != enrichedVideo }
                .associateBy { it.id }
        var updatedSnapshot: List<Video>? = null
        uiState.update { state ->
            val updated =
                state.videos.map { current ->
                    updates[current.id]?.let(current::withChannelMetadataFrom) ?: current
                }
            if (updated == state.videos) return@update state
            updatedSnapshot = updated
            HomeFeedCache.update(updated, state.shorts)
            state.copy(videos = updated)
        }
        return updatedSnapshot
    }

    /**
     * Whenever cards land, asks once per channel still missing its name or avatar, in one bounded
     * round, and writes the list once. Rounds run one at a time; a page landing mid-round waits.
     */
    suspend fun enrichLandedChannels() {
        uiState
            .map { it.videos }
            .distinctUntilChanged()
            .conflate()
            .collect { videos ->
                val asked = channelsToEnrich(videos, channelsAskedAbout)
                if (asked.isEmpty()) return@collect
                val enriched =
                    enrichedByChannel(asked, repository.enrichMissingChannelMetadata(asked, limit = asked.size))
                if (enriched.isEmpty()) return@collect
                uiState.update { state ->
                    val updated = state.videos.withEnrichedChannels(enriched)
                    if (updated == state.videos) return@update state
                    HomeFeedCache.update(updated, state.shorts)
                    state.copy(videos = updated)
                }
            }
    }
}
