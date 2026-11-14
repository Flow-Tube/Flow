package io.github.aedev.flow.data.contentfilter.aislist

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** What the worker needs from the app graph; it is created by WorkManager, not by Hilt. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AiListRefreshEntryPoint {
    fun aiListRepository(): AiListRepository
}

/** About once a day while a switch is on: one manifest request, usually answered 304. */
class AiListRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(applicationContext, AiListRefreshEntryPoint::class.java).aiListRepository()
        return if (repository.refresh() == AiListOutcome.RETRY) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODIC_WORK = "aislist_refresh"
        private const val NOW_WORK = "aislist_refresh_now"
        private const val PERIOD_HOURS = 24L
        private const val FLEX_HOURS = 6L

        private val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

        /**
         * KEEP holds an existing schedule, so app starts do not move it. The first run lands at a
         * random point of the day, which spreads devices out as the list contract asks.
         */
        internal fun schedule(
            context: Context,
            refreshNow: Boolean,
        ) {
            val workManager = WorkManager.getInstance(context)
            val periodic =
                PeriodicWorkRequestBuilder<AiListRefreshWorker>(PERIOD_HOURS, TimeUnit.HOURS, FLEX_HOURS, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .setInitialDelay(Random.nextLong(TimeUnit.HOURS.toMinutes(PERIOD_HOURS)), TimeUnit.MINUTES)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                    .build()
            workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, periodic)
            if (refreshNow) {
                val now =
                    OneTimeWorkRequestBuilder<AiListRefreshWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                        .build()
                workManager.enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.REPLACE, now)
            }
        }

        internal fun cancel(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(PERIODIC_WORK)
            workManager.cancelUniqueWork(NOW_WORK)
        }
    }
}
