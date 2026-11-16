package io.github.aedev.flow.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.BackupRepository
import io.github.aedev.flow.data.local.LocalDataManager
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.profile.ActiveProfile
import io.github.aedev.flow.data.profile.ProfileSlices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Days between automatic backups, or null when they are off. */
fun LocalDataManager.AutoBackupFrequency.periodDays(): Long? =
    when (this) {
        LocalDataManager.AutoBackupFrequency.NONE -> null
        LocalDataManager.AutoBackupFrequency.DAILY -> 1L
        LocalDataManager.AutoBackupFrequency.WEEKLY -> 7L
        LocalDataManager.AutoBackupFrequency.MONTHLY -> 30L
    }

/** What the worker needs from the app graph; it is created by WorkManager, not by Hilt. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoBackupEntryPoint {
    fun backupRepository(): BackupRepository

    fun profileSlices(): ProfileSlices
}

class AutoBackupWorker(
    private val appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    companion object {
        const val WORK_NAME = "auto_backup_work"
        const val NOTIFICATION_CHANNEL_ID = "auto_backup_channel"
        private const val NOTIFICATION_ID_SUCCESS = 5001
        private const val NOTIFICATION_ID_FAILURE = 5002

        fun scheduleBackup(
            context: Context,
            frequencyDays: Long,
        ) {
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, request(frequencyDays))
        }

        private fun request(frequencyDays: Long) =
            PeriodicWorkRequestBuilder<AutoBackupWorker>(frequencyDays, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()

        fun cancelBackup(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /** A restored install brings the schedule back in settings but not in WorkManager, which isn't backed up. */
        suspend fun keepScheduled(context: Context) {
            val days = PlayerPreferences(context).autoBackupFrequency.first().periodDays() ?: return
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request(days))
        }

        /**
         * Puts WorkManager in line with the saved schedule now. A restore replaces the setting, and
         * [keepScheduled] only runs at startup, so the old schedule would otherwise run until then.
         */
        suspend fun applySchedule(context: Context) {
            val days = PlayerPreferences(context).autoBackupFrequency.first().periodDays()
            if (days == null) {
                cancelBackup(context)
            } else {
                WorkManager
                    .getInstance(context)
                    .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request(days))
            }
        }

        /** With two or more profiles only a master backup holds them all, so that is what the schedule writes. */
        fun effectiveType(
            chosen: LocalDataManager.AutoBackupType,
            profileCount: Int,
        ): LocalDataManager.AutoBackupType = if (profileCount >= 2) LocalDataManager.AutoBackupType.MASTER else chosen
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val ldm = LocalDataManager(appContext)
            val playerPrefs = PlayerPreferences(appContext)

            val frequency = playerPrefs.autoBackupFrequency.first()
            if (frequency == LocalDataManager.AutoBackupFrequency.NONE) {
                return@withContext Result.success()
            }

            val folderUriStr =
                playerPrefs.autoBackupFolderUri.first()
                    ?: return@withContext Result.failure()

            val folderUri = Uri.parse(folderUriStr)
            val profileCount =
                ActiveProfile
                    .registry(appContext)
                    .state
                    .first()
                    .profiles.size
            val type = effectiveType(playerPrefs.autoBackupType.first(), profileCount)
            val dependencies = EntryPointAccessors.fromApplication(appContext, AutoBackupEntryPoint::class.java)
            val backupRepo = dependencies.backupRepository()

            val result =
                when (type) {
                    LocalDataManager.AutoBackupType.APP_DATA -> {
                        backupRepo.exportDataToFolder(folderUri)
                    }

                    LocalDataManager.AutoBackupType.BRAIN -> {
                        backupRepo.exportBrainToFolder(folderUri)
                    }

                    LocalDataManager.AutoBackupType.MASTER -> {
                        dependencies.profileSlices().exportMasterToFolder(folderUri)
                    }
                }

            return@withContext if (result.isSuccess) {
                ldm.setAutoBackupLastRun(System.currentTimeMillis())
                showNotification(appContext, success = true)
                Result.success()
            } else {
                showNotification(appContext, success = false)
                Result.retry()
            }
        }

    private fun showNotification(
        context: Context,
        success: Boolean,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            val channel =
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    context.getString(R.string.auto_backup_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            nm.createNotificationChannel(channel)
        }

        val titleRes = if (success) R.string.auto_backup_success else R.string.auto_backup_failed
        val notification =
            NotificationCompat
                .Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(titleRes))
                .setAutoCancel(true)
                .build()

        val notifId = if (success) NOTIFICATION_ID_SUCCESS else NOTIFICATION_ID_FAILURE
        nm.notify(notifId, notification)
    }
}
