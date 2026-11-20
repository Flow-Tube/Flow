package io.github.aedev.flow.ui.screens.player

import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FeedExclusions
import io.github.aedev.flow.data.repository.WatchRelatedPages
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.PlayerRelatedVideosPolicy
import io.github.aedev.flow.ui.screens.player.state.RelatedAppend
import io.github.aedev.flow.ui.screens.player.state.RelatedLanePlacement
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.ui.screens.player.state.appendRelatedPage
import io.github.aedev.flow.ui.screens.player.state.awaitsRelatedPage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Appends the next related page once the viewer reaches the end of the lane, with no cap while
 * YouTube keeps handing out a continuation. One request per page: the lane's own state marks the
 * page in flight, so a repeat call for it is dropped, and a page that lands after the video changed
 * is discarded.
 */
internal class RelatedPagesLoader(
    private val pages: WatchRelatedPages,
    private val uiState: MutableStateFlow<VideoPlayerUiState>,
    private val playerManager: EnhancedPlayerManager,
    private val playerPreferences: PlayerPreferences,
    private val scope: CoroutineScope,
    private val networkDispatcher: CoroutineDispatcher,
    private val shortsEnabled: () -> Boolean,
    private val exclusions: () -> FeedExclusions,
) {
    fun loadMore(videoId: String) {
        var continuation: String? = null
        uiState.update { state ->
            val next = state.relatedContinuation
            if (next == null || state.cachedVideo?.id != videoId || state.relatedAppend == RelatedAppend.LOADING) {
                continuation = null
                return@update state
            }
            continuation = next
            state.copy(relatedAppend = RelatedAppend.LOADING)
        }
        val token = continuation ?: return

        scope.launch(networkDispatcher) {
            val page = pages.nextPage(token)
            if (page == null) {
                uiState.update { if (it.awaitsRelatedPage(videoId, token)) it.copy(relatedAppend = RelatedAppend.FAILED) else it }
                return@launch
            }
            val kept = unwatched(PlayerRelatedVideosPolicy.sanitize(videoId, page.videos, shortsEnabled(), exclusions()))
            val collections = RelatedLanePlacement.visible(videoId, page.collections, shortsEnabled(), exclusions())
            var lane: List<Video>? = null
            uiState.update { state ->
                if (!state.awaitsRelatedPage(videoId, token)) return@update state
                state.appendRelatedPage(page.videos, kept, collections, page.continuation).also { lane = it.relatedVideos }
            }
            // The queue follows the lane, so autoplay can run on into the pages the viewer opened.
            lane?.let { videos ->
                playerManager.setAutoplayCandidates(videoId, videos, playerPreferences.autoplayEnabled.first())
            }
        }
    }

    /** Unlike the first page, a later one drops watched videos outright: the lane already has others. */
    private suspend fun unwatched(videos: List<Video>): List<Video> {
        val watched = playerManager.watchedSuggestionsSource?.watchedAmong(videos).orEmpty()
        return if (watched.isEmpty()) videos else videos.filterNot { it.id in watched }
    }
}
