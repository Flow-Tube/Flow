package io.github.aedev.flow.notification

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.update.UpdateAnnouncement
import io.github.aedev.flow.data.update.UpdateEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Worker that checks for application updates in the background.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    companion object {
        const val WORK_NAME = "update_check_work"
        private const val TAG = "UpdateCheckWorker"

        /** Schedules the 12-hour check; [BackgroundCheckScheduler] decides whether it should run at all. */
        fun schedulePeriodicCheck(
            context: Context,
            reschedule: Boolean,
        ) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()

            val workRequest =
                PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                    12,
                    TimeUnit.HOURS,
                ).setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        WorkRequest.MIN_BACKOFF_MILLIS,
                        TimeUnit.MILLISECONDS,
                    ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                periodicWorkPolicy(reschedule),
                workRequest,
            )
            Log.d(TAG, "Scheduled update check every 12 hours")
        }

        fun cancelScheduledChecks(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled scheduled update checks")
        }
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            if (!BuildConfig.UPDATER_ENABLED || BuildConfig.DEBUG) return@withContext Result.success()
            val preferences = PlayerPreferences(applicationContext)
            if (!BackgroundCheckScheduler.updateCheckWanted(
                    preferences.notificationsEnabled.first(),
                    preferences.notifUpdatesEnabled.first(),
                )
            ) {
                Log.d(TAG, "Update notifications are off, skipping update check")
                return@withContext Result.success()
            }
            // Announcing marks the version as told, so it must not happen while the notification can't show.
            if (!NotificationHelper.hasNotificationPermission(applicationContext)) return@withContext Result.success()
            val updates = EntryPointAccessors.fromApplication(applicationContext, UpdateEntryPoint::class.java).updateRepository()
            val release = updates.releaseToAnnounce(UpdateAnnouncement.NOTIFICATION)
            if (release != null) NotificationHelper.showUpdateNotification(applicationContext, release)
            Result.success()
        }
}
