package io.github.aedev.flow.player.stream

import android.os.SystemClock
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.utils.potoken.WebPoTokenSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Starts stream extraction the moment a video is tapped, ahead of the player screen composing and
 * the ViewModel running its load.
 *
 * This buys latency without buying network: a load that arrives while the extraction runs joins it
 * through [InFlightRequestCoalescer], and one that arrives after it finished takes its result
 * through [take] instead of running the client ladder again. Navigation, composition and the
 * player screen's own setup overlap with the extraction rather than following it.
 */
object PlaybackPrefetcher {
    private val tap =
        TapPrefetch(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            extract = { videoId -> InnerTubeVideoStreamExtractor.extract(videoId) },
            identity = { WebPoTokenSession.identityGeneration },
            nowMs = SystemClock::elapsedRealtime,
        )

    /**
     * Warms extraction for [videoId]. Safe to call from the main thread; returns immediately.
     *
     * Local media plays from a `content://` URI and never touches extraction, so those ids are
     * ignored rather than sent through a client ladder that would certainly fail.
     */
    fun prefetch(videoId: String) {
        if (videoId.isBlank() || LocalMediaIds.isLocal(videoId)) return
        tap.prefetch(videoId)
    }

    /** The tap's finished extraction of [videoId] for the load that follows it, once. */
    internal fun take(videoId: String): InnerTubeVideoStreamExtractor.VideoExtractionResult? = tap.take(videoId)
}
