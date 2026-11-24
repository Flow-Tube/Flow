package io.github.aedev.flow.data.video.downloader.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The WorkManager work that drains the download queue. It is unique, so there is at most one, and
 * it is persisted, so a queue the app was killed in the middle of starts again on its own. The
 * network it needs (any, or unmetered for Wi-Fi only) is its constraint.
 */
class DownloadQueueWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    /** What the worker needs from the app graph; it is created by WorkManager, not by Hilt. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun downloadQueue(): DownloadQueue

        fun downloadNotifier(): DownloadNotifier

        fun downloadWaits(): DownloadWaits

        fun downloadController(): DownloadController
    }

    private val dependencies by lazy { EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java) }

    override suspend fun doWork(): Result {
        val notifier = dependencies.downloadNotifier()
        val queue = dependencies.downloadQueue()
        notifier.createChannel()
        promote(notifier, active = 0)
        coroutineScope {
            val summary = launch { queue.active.collect { promote(notifier, it) } }
            try {
                queue.drain()
            } finally {
                summary.cancel()
            }
        }
        dependencies.downloadWaits().nextRetryInMs()?.let { dependencies.downloadController().kickAfter(it) }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(dependencies.downloadNotifier(), active = 0)

    /**
     * Runs the queue as a foreground service while the app may start one. From the background
     * Android 12+ refuses it; the work then runs within the job time limit, and anything still
     * queued when it is stopped resumes from its saved blocks on the next run.
     */
    private suspend fun promote(
        notifier: DownloadNotifier,
        active: Int,
    ) {
        try {
            setForeground(foregroundInfo(notifier, active))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Download queue runs without a foreground service", e)
        }
    }

    private fun foregroundInfo(
        notifier: DownloadNotifier,
        active: Int,
    ): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                DownloadNotifier.SUMMARY_NOTIFICATION_ID,
                notifier.summary(active),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(DownloadNotifier.SUMMARY_NOTIFICATION_ID, notifier.summary(active))
        }

    companion object {
        const val UNIQUE_NAME = "flow_download_queue"
        const val TAG = "DownloadQueueWorker"
    }
}
