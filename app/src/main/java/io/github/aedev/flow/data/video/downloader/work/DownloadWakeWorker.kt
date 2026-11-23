package io.github.aedev.flow.data.video.downloader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Wakes the download queue once a waiting download may run again. It is separate from the queue
 * work, so a download queued in the meantime starts at once instead of waiting behind it.
 */
class DownloadWakeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun downloadController(): DownloadController
    }

    override suspend fun doWork(): Result {
        EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).downloadController().kick()
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "flow_download_wake"
    }
}
