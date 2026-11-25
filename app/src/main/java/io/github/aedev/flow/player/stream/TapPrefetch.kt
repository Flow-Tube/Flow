package io.github.aedev.flow.player.stream

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The extraction a tap started, held for the player load that follows it.
 *
 * The load joins a prefetch still in flight through [InFlightRequestCoalescer], but the coalescer
 * forgets a request once it completes, so a load arriving after a fast client answered ran the
 * whole client ladder again. A finished prefetch is therefore offered to that one load, as
 * YouTube's player does with its early fetch: taken once, within [MAX_AGE_MS], and never across a
 * visitor identity change. The first load of the video spends the offer whether or not it was
 * ready, so a reload after a refusal always extracts fresh URLs.
 */
internal class TapPrefetch(
    private val scope: CoroutineScope,
    private val extract: suspend (String) -> InnerTubeVideoStreamExtractor.VideoExtractionResult?,
    private val identity: () -> Int,
    nowMs: () -> Long,
) {
    private val lock = Any()
    private val handoff = ExtractionHandoff(MAX_AGE_MS, identity, nowMs)
    private var inFlight: Job? = null

    // The tapped video whose result is still to be offered; cleared once its load has asked.
    private var pendingVideoId: String? = null

    fun prefetch(videoId: String) {
        synchronized(lock) {
            if (handoff.holds(videoId)) return
            if (pendingVideoId == videoId && inFlight?.isActive == true) return
            // Only the most recently tapped video is worth warming. Leaving an abandoned one
            // running would have it compete for bandwidth with the video actually being opened.
            inFlight?.cancel()
            pendingVideoId = videoId
            val generation = identity()
            inFlight = scope.launch { offerWhenDone(videoId, generation) }
        }
    }

    /** The tap's finished extraction of [videoId], once; null while it still runs or after it expired. */
    fun take(videoId: String): InnerTubeVideoStreamExtractor.VideoExtractionResult? =
        synchronized(lock) {
            if (pendingVideoId == videoId) pendingVideoId = null
            handoff.take(videoId)
        }

    private suspend fun offerWhenDone(
        videoId: String,
        generation: Int,
    ) {
        val result =
            try {
                extract(videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "prefetch for $videoId ended: ${e.javaClass.simpleName}")
                null
            } ?: return
        synchronized(lock) {
            if (pendingVideoId != videoId) return
            pendingVideoId = null
            if (identity() == generation) handoff.offer(videoId, result)
        }
    }

    companion object {
        private const val TAG = "PlaybackPrefetcher"
        const val MAX_AGE_MS = 10_000L
    }
}
