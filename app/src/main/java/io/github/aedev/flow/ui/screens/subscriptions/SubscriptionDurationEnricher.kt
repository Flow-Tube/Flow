package io.github.aedev.flow.ui.screens.subscriptions

import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

private const val DURATION_ENRICHMENT_BATCH_SIZE = 3
private const val DURATION_METADATA_TIMEOUT_MS = 4_000L
private const val DURATION_RETRY_AFTER_MS = 30 * 60 * 1_000L

/**
 * Fills in durations for the feed rows on screen, which RSS leaves out, a few lookups at a time. One run at a time;
 * a scroll during a run is picked up when it ends. Called on the main thread.
 */
internal class SubscriptionDurationEnricher(
    private val scope: CoroutineScope,
    private val playerPreferences: PlayerPreferences,
    private val recentVideos: () -> List<Video>,
    private val onEnriched: suspend (Map<String, Video>) -> Unit,
) {
    private val attemptedAt = mutableMapOf<String, Long>()
    private var job: Job? = null
    private var visibleVideoIds: Set<String> = emptySet()
    private var hasPendingVisible = false

    fun updateVisibleVideoIds(videoIds: Set<String>) {
        visibleVideoIds = videoIds
        if (videoIds.isEmpty()) {
            hasPendingVisible = false
            job?.cancel()
            job = null
            return
        }
        scheduleVisible()
    }

    private fun scheduleVisible() {
        if (job?.isActive == true) {
            hasPendingVisible = true
            return
        }

        val nowMillis = System.currentTimeMillis()
        val visibleWindow =
            visibleSubscriptionEnrichmentWindow(
                videos = recentVideos(),
                visibleVideoIds = visibleVideoIds,
            )
        val candidates =
            missingDurationCandidates(
                videos = visibleWindow,
                attemptedAtMillis = attemptedAt,
                nowMillis = nowMillis,
                retryAfterMillis = DURATION_RETRY_AFTER_MS,
            )
        if (candidates.isEmpty()) return

        hasPendingVisible = false
        job =
            scope.launch(PerformanceDispatcher.networkIO) {
                val runningJob = coroutineContext[Job]
                try {
                    val enrichedById = mutableMapOf<String, Video>()
                    candidates.chunked(DURATION_ENRICHMENT_BATCH_SIZE).forEach { batch ->
                        val lookups =
                            supervisorScope {
                                batch
                                    .map { video ->
                                        async(PerformanceDispatcher.networkIO) {
                                            fetchSubscriptionPlayerMetadata(video, DURATION_METADATA_TIMEOUT_MS)
                                        }
                                    }.awaitAll()
                            }.filterNotNull()
                        withContext(Dispatchers.Main.immediate) {
                            val attempted = System.currentTimeMillis()
                            batch.forEach { video ->
                                attemptedAt[video.id] = attempted
                            }
                        }
                        lookups.filter { it.refusedToViewer }.forEach { playerPreferences.markVideoUnplayable(it.videoId) }
                        lookups.mapNotNull { it.enriched }.associateByTo(enrichedById) { it.id }
                    }

                    if (enrichedById.isNotEmpty()) onEnriched(enrichedById)
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        if (job === runningJob) {
                            job = null
                            if (hasPendingVisible && visibleVideoIds.isNotEmpty()) {
                                scheduleVisible()
                            }
                        }
                    }
                }
            }
    }
}
