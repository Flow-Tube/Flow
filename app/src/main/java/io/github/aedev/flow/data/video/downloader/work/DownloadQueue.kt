package io.github.aedev.flow.data.video.downloader.work

import android.util.Log
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.dao.DownloadDao
import io.github.aedev.flow.data.local.dao.QueuedDownload
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drains the download queue kept in Room: runs up to the configured number of downloads at once,
 * oldest first, stops a running download the moment its row is paused or removed, and returns once
 * nothing is left waiting. Rows are the only state, so a queue the app was killed in the middle of
 * is picked up exactly where it was.
 */
@Singleton
class DownloadQueue
    @Inject
    constructor(
        private val downloadDao: DownloadDao,
        private val runner: DownloadRunner,
        private val preferences: PlayerPreferences,
        private val waits: DownloadWaits,
    ) {
        /** How many downloads are transferring right now, for the queue's foreground notification. */
        val active = MutableStateFlow(0)

        suspend fun drain() {
            downloadDao.requeueInterrupted()
            coroutineScope {
                val running = mutableMapOf<String, Job>()
                val finished = MutableStateFlow(0)
                var wake: Job? = null
                val limit = MutableStateFlow(preferences.concurrentDownloads.first().coerceIn(MIN_PARALLEL, MAX_PARALLEL))
                val limitWatch =
                    launch { preferences.concurrentDownloads.collect { limit.value = it.coerceIn(MIN_PARALLEL, MAX_PARALLEL) } }
                try {
                    combine(downloadDao.observeQueue(), limit, finished) { rows, max, _ -> rows to max }
                        .first { (rows, max) ->
                            synchronized(running) {
                                val held = waits.held()
                                reconcile(rows, max, running, held) { videoId ->
                                    launch {
                                        try {
                                            runner.run(videoId)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Download $videoId crashed", e)
                                            withContext(
                                                NonCancellable,
                                            ) { downloadDao.updateAllItemsStatus(videoId, DownloadItemStatus.FAILED) }
                                        } finally {
                                            synchronized(running) { running.remove(videoId) }
                                            finished.update { it + 1 }
                                        }
                                    }
                                }
                                active.value = running.size
                                val idle = running.isEmpty() && rows.none { it.status == DownloadItemStatus.PENDING && it.videoId !in held }
                                // A short wait is sat out here; a long one ends the work, which the worker re-schedules.
                                val next = waits.nextRetryInMs()?.takeIf { !idle || it <= IN_PROCESS_WAIT_MS }
                                wake?.cancel()
                                wake =
                                    next?.let { delayMs ->
                                        launch {
                                            delay(delayMs)
                                            finished.update { it + 1 }
                                        }
                                    }
                                idle && next == null
                            }
                        }
                } finally {
                    wake?.cancel()
                    limitWatch.cancel()
                    active.value = 0
                }
            }
        }

        private fun reconcile(
            rows: List<QueuedDownload>,
            limit: Int,
            running: MutableMap<String, Job>,
            held: Set<String>,
            start: (String) -> Job,
        ) {
            val plan = DownloadQueuePlan.of(rows, running.keys, limit, held)
            plan.toStop.forEach { videoId -> running.remove(videoId)?.cancel() }
            plan.toStart.forEach { videoId -> running[videoId] = start(videoId) }
        }

        private companion object {
            const val TAG = "DownloadQueue"
            const val MIN_PARALLEL = 1
            const val MAX_PARALLEL = 5
            const val IN_PROCESS_WAIT_MS = 60_000L
        }
    }

/** Which downloads the queue starts and stops for one look at its rows. Pure, so it is unit tested. */
internal data class DownloadQueuePlan(
    val toStart: List<String>,
    val toStop: List<String>,
) {
    companion object {
        // A job whose row finished or failed is completing on its own and is never stopped.
        private val STOP_REQUESTS = setOf(DownloadItemStatus.PAUSED, DownloadItemStatus.CANCELLED)

        fun of(
            rows: List<QueuedDownload>,
            running: Set<String>,
            limit: Int,
            held: Set<String> = emptySet(),
        ): DownloadQueuePlan {
            val statuses = rows.associate { it.videoId to it.status }
            val toStop = running.filter { statuses[it] == null || statuses[it] in STOP_REQUESTS }
            val stillRunning = running.size - toStop.size
            val toStart =
                rows
                    .asSequence()
                    .filter { it.status == DownloadItemStatus.PENDING && it.videoId !in running && it.videoId !in held }
                    .map { it.videoId }
                    .take((limit - stillRunning).coerceAtLeast(0))
                    .toList()
            return DownloadQueuePlan(toStart, toStop)
        }
    }
}
