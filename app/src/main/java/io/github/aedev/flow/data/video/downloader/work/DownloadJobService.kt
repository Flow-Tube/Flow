package io.github.aedev.flow.data.video.downloader.work

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Runs the download queue as a user-initiated data transfer job on Android 14+, the host YouTube
 * moved its offline transfers to: it has no fixed runtime cap while it makes progress, unlike work
 * started from the background (about 10 minutes) or a dataSync service (6 hours a day on 15).
 * Scheduled only from the app on screen; anything else still goes through [DownloadQueueWorker].
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@AndroidEntryPoint
class DownloadJobService : JobService() {
    @Inject lateinit var queue: DownloadQueue

    @Inject lateinit var notifier: DownloadNotifier

    @Inject lateinit var waits: DownloadWaits

    @Inject lateinit var controller: DownloadController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        notifier.createChannel()
        post(params, active = 0)
        running =
            scope.launch {
                val summary = launch { queue.active.collect { post(params, it) } }
                try {
                    queue.drain()
                } finally {
                    summary.cancel()
                }
                waits.nextRetryInMs()?.let { controller.kickAfter(it) }
                jobFinished(params, false)
            }
        return true
    }

    // Stopped by the system (network lost, quota): the running downloads keep their blocks and the
    // job is rescheduled with the platform's backoff.
    override fun onStopJob(params: JobParameters): Boolean {
        waits.stoppedForNetwork = params.stopReason == JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY
        running?.cancel()
        return true
    }

    // The transfer follows the default network itself; a changed one needs nothing from the job.
    override fun onNetworkChanged(params: JobParameters) = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun post(
        params: JobParameters,
        active: Int,
    ) {
        setNotification(params, DownloadNotifier.SUMMARY_NOTIFICATION_ID, notifier.summary(active), JOB_END_NOTIFICATION_POLICY_REMOVE)
    }

    companion object {
        private const val TAG = "DownloadJobService"
        private const val JOB_ID = 72_401

        /**
         * Schedules the job unless one is already pending or running, and reports whether the queue
         * is now in its hands. The system refuses a user-initiated job while the app is not visible.
         */
        fun schedule(
            context: Context,
            wifiOnly: Boolean,
        ): Boolean {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            if (scheduler.getPendingJob(JOB_ID) != null) return true
            val network =
                NetworkRequest
                    .Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .apply { if (wifiOnly) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
                    .build()
            val job =
                JobInfo
                    .Builder(JOB_ID, ComponentName(context, DownloadJobService::class.java))
                    .setUserInitiated(true)
                    .setRequiredNetwork(network)
                    .build()
            return try {
                scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
            } catch (e: RuntimeException) {
                Log.w(TAG, "User-initiated download job refused", e)
                false
            }
        }

        /** Drops a pending job whose network rule changed; the caller schedules it again. */
        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}
