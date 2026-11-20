package io.github.aedev.flow.data.repository

import io.github.aedev.flow.data.model.RelatedCollection
import io.github.aedev.flow.data.model.WatchRelatedPage
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.response.WatchMetadataResponse
import io.github.aedev.flow.innertube.pages.WatchRelatedEntry
import io.github.aedev.flow.innertube.pages.WatchRelatedResults
import io.github.aedev.flow.innertube.pages.relatedContinuationResults
import io.github.aedev.flow.innertube.pages.watchRelatedResults
import io.github.aedev.flow.innertube.pages.watchUpNextCard
import io.github.aedev.flow.innertube.pages.watchUpNextVideoId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The player's related lane, read off the one watch response [YouTubeRepository.watchNextResponse]
 * already caches for the description and comments, so the lane costs no request of its own.
 */
internal class WatchRelatedPages(
    private val repository: YouTubeRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Null when the cached response lists no videos; the caller then takes its older fallback. */
    suspend fun firstPage(videoId: String): WatchRelatedPage? =
        withContext(dispatcher) {
            val watch = repository.watchNextResponse(videoId) ?: return@withContext null
            val upNextVideoId = watch.watchUpNextVideoId()
            val upNextCard = watch.watchUpNextCard()?.takeIf { it.videoId == upNextVideoId }
            toPage(watch.watchRelatedResults(YouTube.locale.hl), upNextVideoId, upNextCard).takeIf { it.videos.isNotEmpty() }
        }

    /** The page [continuation] opens, or null when the request failed. */
    suspend fun nextPage(continuation: String): WatchRelatedPage? =
        withContext(dispatcher) {
            YouTube
                .relatedContinuationJson(continuation)
                .getOrNull()
                ?.let { toPage(it.relatedContinuationResults(YouTube.locale.hl), upNextVideoId = null) }
        }

    /** [upNextCard] heads the page when YouTube's pick is not among the videos it lists. */
    private suspend fun toPage(
        results: WatchRelatedResults,
        upNextVideoId: String?,
        upNextCard: WatchMetadataResponse.CompactVideo? = null,
    ): WatchRelatedPage {
        val seen = mutableSetOf<String>()
        val compact = mutableListOf<WatchMetadataResponse.CompactVideo>()
        val listed =
            results.entries
                .filterIsInstance<WatchRelatedEntry.Videos>()
                .flatMap { it.videos }
                .mapNotNull { it.videoId }
        if (upNextCard != null && upNextVideoId !in listed && seen.add(upNextVideoId.orEmpty())) compact += upNextCard
        val collections = mutableListOf<RelatedCollection>()
        results.entries.forEach { entry ->
            when (entry) {
                is WatchRelatedEntry.Videos -> {
                    entry.videos.forEach { video ->
                        val id = video.videoId?.takeIf { it.isNotBlank() } ?: return@forEach
                        if (seen.add(id)) compact += video
                    }
                }

                is WatchRelatedEntry.PlaylistCard -> {
                    collections += RelatedCollection.PlaylistCard(seen.size, entry.playlist, entry.labels)
                }

                is WatchRelatedEntry.ShortsShelf -> {
                    collections += RelatedCollection.ShortsShelf(seen.size, entry.title, entry.shorts)
                }
            }
        }
        val videos =
            if (compact.isEmpty()) {
                emptyList()
            } else {
                repository.enrichLikelyCollabAvatarStacks(WatchMetadataVideoMapper.relatedVideos(responseOf(compact)))
            }
        return WatchRelatedPage(
            videos = videos,
            upNextVideoId = upNextVideoId,
            continuation = results.continuation,
            collections = collections,
        )
    }

    /** The shape [WatchMetadataVideoMapper] reads, so every page maps its cards the one way. */
    private fun responseOf(videos: List<WatchMetadataResponse.CompactVideo>) =
        WatchMetadataResponse(
            contents =
                WatchMetadataResponse.Contents(
                    twoColumnWatchNextResults =
                        WatchMetadataResponse.TwoColumn(
                            secondaryResults =
                                WatchMetadataResponse.SecondaryWrap(
                                    WatchMetadataResponse.SecondaryInner(
                                        videos.map { WatchMetadataResponse.SecondaryItem(compactVideoRenderer = it) },
                                    ),
                                ),
                        ),
                ),
        )
}
