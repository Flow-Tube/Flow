package io.github.aedev.flow.ui.screens.shorts

import android.util.Log
import io.github.aedev.flow.data.model.ShortVideo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.toVideo
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.recommendation.InteractionType
import io.github.aedev.flow.data.shorts.ShortWatchClassifier
import io.github.aedev.flow.data.shorts.ShortsWatchHistory
import io.github.aedev.flow.data.stats.VideoStatsRecorder
import io.github.aedev.flow.data.stats.ViewEvent
import io.github.aedev.flow.data.stats.ViewFormat
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a reel leaves behind once watched or swiped away: history, the recap's view, and the engine's signal. */
internal class ShortsWatchSignals(
    private val scope: CoroutineScope,
    private val watchHistory: ShortsWatchHistory,
    private val videoStats: VideoStatsRecorder,
) {
    fun recordProgress(
        short: ShortVideo,
        positionMs: Long,
        durationMs: Long,
    ) {
        scope.launch(PerformanceDispatcher.diskIO) {
            val safeDuration =
                when {
                    durationMs > 0L -> durationMs
                    short.durationMs >= 1_000L -> short.durationMs
                    else -> DEFAULT_REEL_DURATION_MS
                }
            watchHistory.save(short, positionMs.coerceAtLeast(1_000L).coerceAtMost(safeDuration), safeDuration)
        }
    }

    /**
     * Terminal signal for a short the user swiped away from before the watch threshold fired.
     * Early abandonment emits SKIPPED — the engine's main source of negative watch evidence.
     */
    fun recordAbandoned(
        short: ShortVideo,
        positionMs: Long,
        durationMs: Long,
    ) {
        scope.launch(PerformanceDispatcher.diskIO) {
            val video = short.toVideo()
            val signal = ShortWatchClassifier.classifyAbandon(positionMs, durationMs, video.duration)
            recordView(
                video,
                positionMs,
                counted =
                    signal?.interaction != InteractionType.SKIPPED && positionMs >= ShortWatchClassifier.MIN_SHORT_WATCH_MS,
            )
            signal ?: return@launch
            runCatching {
                FlowNeuroEngine.onVideoInteraction(video.copy(isShort = true), signal.interaction, percentWatched = signal.percent)
                FlowNeuroEngine.recordSeenShorts(listOf(video.id))
            }.onFailure { e -> Log.w(TAG, "Failed to record abandoned short in FlowNeuro", e) }
        }
    }

    fun recordWatched(
        short: ShortVideo,
        positionMs: Long,
        durationMs: Long,
    ) {
        scope.launch(PerformanceDispatcher.diskIO) {
            val video = short.toVideo()
            val signal = ShortWatchClassifier.classify(positionMs, durationMs, video.duration)
            recordView(video, signal.position, counted = signal.interaction == InteractionType.WATCHED)
            watchHistory.save(short, signal.position, signal.safeDuration)

            runCatching {
                FlowNeuroEngine.onVideoInteraction(video.copy(isShort = true), signal.interaction, percentWatched = signal.percent)
                FlowNeuroEngine.recordSeenShorts(listOf(video.id))
            }.onFailure { e -> Log.w(TAG, "Failed to record watched short in FlowNeuro", e) }
        }
    }

    private fun recordView(
        video: Video,
        watchedMs: Long,
        counted: Boolean,
    ) = videoStats.onView(
        ViewEvent(
            videoId = video.id,
            title = video.title,
            channelId = video.channelId,
            channelName = video.channelName,
            format = ViewFormat.SHORT,
            watchedMs = watchedMs,
            counted = counted,
            skipped = !counted,
            channelAvatarUrl = video.channelThumbnailUrl,
        ),
        video.copy(isShort = true),
    )

    private companion object {
        const val TAG = "ShortsViewModel"
        const val DEFAULT_REEL_DURATION_MS = 60_000L
    }
}
