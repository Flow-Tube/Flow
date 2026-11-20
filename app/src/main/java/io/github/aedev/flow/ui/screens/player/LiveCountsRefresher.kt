package io.github.aedev.flow.ui.screens.player

import android.os.SystemClock
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.innertube.pages.LiveMetadataUpdate
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Keeps a live video's viewers, likes and date current while its watch page is on screen.
 *
 * [run] belongs to the caller's coroutine, so the page decides when it runs: it is cancelled the
 * moment the page collapses, goes fullscreen or to picture-in-picture, or the screen turns off, and
 * the cursor keeps its place for when the page comes back. Called on the main thread only.
 */
internal class LiveCountsRefresher(
    private val repository: YouTubeRepository,
    private val uiState: MutableStateFlow<VideoPlayerUiState>,
    private val networkDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var cursor: LiveCountsCursor? = null

    suspend fun run(videoId: String) {
        // The live flag can land before the page's own video does; wait for it instead of giving up.
        val state = uiState.first { it.cachedVideo?.id == videoId }
        val endpoint = state.watchHeader?.updatedMetadata ?: return
        if (!state.isLive) return

        var current = cursor?.takeIf { it.videoId == videoId } ?: LiveCountsPolicy.start(videoId, endpoint.initialDelayMs, clock())
        cursor = current
        while (!current.stopped) {
            delay(LiveCountsPolicy.waitMs(current, clock()))
            val update =
                withContext(networkDispatcher) { repository.liveMetadataUpdate(endpoint.videoId, current.continuation) }
            if (uiState.value.cachedVideo?.id != videoId) return
            update?.let { apply(videoId, it) }
            current = LiveCountsPolicy.after(current, update, clock())
            cursor = current
        }
    }

    private fun apply(
        videoId: String,
        update: LiveMetadataUpdate,
    ) {
        uiState.update { state ->
            val video = state.cachedVideo?.takeIf { it.id == videoId } ?: return@update state
            val refreshed =
                video.copy(
                    viewCount = update.viewCount ?: video.viewCount,
                    likeCount = update.likeCount ?: video.likeCount,
                    uploadDate = update.dateText ?: video.uploadDate,
                )
            if (refreshed == video) state else state.copy(cachedVideo = refreshed)
        }
    }
}
