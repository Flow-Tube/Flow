package io.github.aedev.flow.sync2.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.data.profile.ProfileWork
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.lan.LanSync
import java.util.concurrent.TimeUnit

/** Opening the app syncs at most this often; the periodic run doesn't wait for it. */
private const val OPEN_INTERVAL_MS = 5 * 60 * 1000L

/**
 * Syncs linked devices on app open and every hour, only with a network, looks for them on the same
 * Wi-Fi every hour, and keeps itself scheduled while linked.
 */
class LinkedSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun linkedSync(): LinkedSync

        fun linkedDatabase(): LinkedDatabase

        fun lanSync(): LanSync

        fun syncIndicator(): SyncIndicator
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).syncIndicator().foregroundInfo()

    override suspend fun doWork(): Result {
        if (!ProfileWork.belongsHere(this)) return Result.success()
        val dependencies = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        if (dependencies.linkedDatabase().groups().group() == null) {
            cancel(applicationContext)
            return Result.success()
        }
        schedule(applicationContext)
        if (tags.contains(LAN_TAG)) {
            dependencies.lanSync().attempt()
            return Result.success()
        }
        val sync = dependencies.linkedSync()
        if (tags.contains(OPEN_TAG) && sync.lastSync.value.let { it != null && System.currentTimeMillis() - it.atMs < OPEN_INTERVAL_MS }) {
            return Result.success()
        }
        return if (sync.syncNow(announce = inputData.getBoolean(ANNOUNCE, false)).error?.retryable ==
            true
        ) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        private const val PERIODIC_NAME = "linked_sync"
        private const val OPEN_NAME = "linked_sync_open"
        private const val OPEN_TAG = "linked_sync_open"
        private const val LAN_NAME = "linked_sync_lan"
        private const val LAN_TAG = "linked_sync_lan"
        private const val SOON_NAME = "linked_sync_soon"
        private const val ANNOUNCE = "announce"

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val work =
                PeriodicWorkRequestBuilder<LinkedSyncWorker>(1, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .setInputData(ProfileWork.data(context))
                    .addTag(ProfileWork.tag(context))
                    .build()
            // A short look for linked devices on the same network, only on Wi-Fi or another unmetered network.
            val lan =
                PeriodicWorkRequestBuilder<LinkedSyncWorker>(1, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                    .setInputData(ProfileWork.data(context))
                    .addTag(ProfileWork.tag(context))
                    .addTag(LAN_TAG)
                    .build()
            WorkManager.getInstance(context).run {
                enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, work)
                enqueueUniquePeriodicWork(LAN_NAME, ExistingPeriodicWorkPolicy.KEEP, lan)
            }
        }

        /** On app open; the worker does nothing when this device isn't linked. */
        fun syncOnOpen(context: Context) {
            val work =
                OneTimeWorkRequestBuilder<LinkedSyncWorker>()
                    .setConstraints(constraints)
                    .setInputData(ProfileWork.data(context))
                    .addTag(ProfileWork.tag(context))
                    .addTag(OPEN_TAG)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(OPEN_NAME, ExistingWorkPolicy.KEEP, work)
        }

        /**
         * Sync now and leaving the app: starts at once and outlives the screen that asked for it.
         * [announce] for Sync now, which says what arrived.
         */
        fun syncSoon(
            context: Context,
            announce: Boolean = false,
        ) {
            val work =
                OneTimeWorkRequestBuilder<LinkedSyncWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setConstraints(constraints)
                    .setInputData(
                        Data
                            .Builder()
                            .putAll(ProfileWork.data(context))
                            .putBoolean(ANNOUNCE, announce)
                            .build(),
                    ).addTag(ProfileWork.tag(context))
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON_NAME, ExistingWorkPolicy.KEEP, work)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).run {
                cancelUniqueWork(SOON_NAME)
                cancelUniqueWork(PERIODIC_NAME)
                cancelUniqueWork(OPEN_NAME)
                cancelUniqueWork(LAN_NAME)
            }
        }
    }
}
