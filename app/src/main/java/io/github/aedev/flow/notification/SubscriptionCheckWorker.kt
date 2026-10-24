package io.github.aedev.flow.notification

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
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
import io.github.aedev.flow.data.local.ChannelSubscription
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.shorts.ChannelReelIndex
import io.github.aedev.flow.data.subscriptions.ChannelRssClient
import io.github.aedev.flow.data.subscriptions.ChannelRssParser
import io.github.aedev.flow.data.subscriptions.SubscriptionFeedRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that checks for new videos from subscribed channels using lightweight RSS
 * feeds, and seeds whatever it finds into the subscription feed cache.
 *
 * It shares [ChannelRssClient] with the in-app feed refresh, so both halves use one connection
 * pool, one timeout policy and one parser for the same endpoint.
 */
class SubscriptionCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    /**
     * WorkManager constructs this worker itself, so the dependencies are pulled from the singleton
     * graph here rather than injected. Using the graph keeps a single [SubscriptionFeedRepository]
     * and therefore a single refresh lock shared with the UI.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun subscriptionRepository(): SubscriptionRepository

        fun subscriptionFeedRepository(): SubscriptionFeedRepository

        fun channelRssClient(): ChannelRssClient

        fun channelReelIndex(): ChannelReelIndex

        fun playerPreferences(): PlayerPreferences
    }

    companion object {
        const val WORK_NAME = "subscription_check_work_v2"
        private const val LEGACY_WORK_NAME = "subscription_check_work"
        private const val IMMEDIATE_WORK_NAME = "subscription_check_work_now"
        private const val TAG = "SubscriptionCheckWorker"

        /** How many channels are polled concurrently. */
        private const val CHANNEL_CHUNK_SIZE = 10

        /** Schedules the periodic check; [BackgroundCheckScheduler] decides whether it should run at all. */
        fun schedulePeriodicCheck(
            context: Context,
            intervalMinutes: Long,
            reschedule: Boolean,
        ) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

            val workRequest =
                PeriodicWorkRequestBuilder<SubscriptionCheckWorker>(
                    intervalMinutes,
                    TimeUnit.MINUTES,
                ).setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        WorkRequest.MIN_BACKOFF_MILLIS,
                        TimeUnit.MILLISECONDS,
                    ).build()

            WorkManager.getInstance(context).apply {
                cancelUniqueWork(LEGACY_WORK_NAME)
                enqueueUniquePeriodicWork(
                    WORK_NAME,
                    periodicWorkPolicy(reschedule),
                    workRequest,
                )
            }

            Log.d(TAG, "Scheduled periodic subscription check every $intervalMinutes minutes")
        }

        /**
         * Cancel scheduled subscription checks
         */
        fun cancelScheduledChecks(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(WORK_NAME)
                cancelUniqueWork(LEGACY_WORK_NAME)
            }
            Log.d(TAG, "Cancelled scheduled subscription checks")
        }

        /**
         * Run an immediate one-time check.
         */
        fun runImmediateCheck(context: Context) {
            val workRequest =
                OneTimeWorkRequestBuilder<SubscriptionCheckWorker>()
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                workRequest,
            )
            Log.d(TAG, "Started immediate subscription check")
        }
    }

    private val dependencies: Dependencies by lazy {
        EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting subscription check via RSS...")

            val preferences = dependencies.playerPreferences()
            if (!BackgroundCheckScheduler.subscriptionCheckWanted(
                    preferences.notificationsEnabled.first(),
                    preferences.notifNewVideosEnabled.first(),
                )
            ) {
                Log.d(TAG, "New video notifications are off, skipping subscription check")
                return@withContext Result.success()
            }

            try {
                val subscriptionRepository = dependencies.subscriptionRepository()
                val subscriptions =
                    subscriptionRepository
                        .getAllSubscriptions()
                        .first()
                        .filter { it.isNotificationEnabled }

                if (subscriptions.isEmpty()) {
                    Log.d(TAG, "No subscriptions with notifications enabled to check")
                    return@withContext Result.success()
                }

                Log.d(TAG, "Checking ${subscriptions.size} subscriptions")

                val announceReels = preferences.effectiveSubscriptionShowShorts.first()
                val notifyShorts = preferences.notifShortsEnabled.first()

                val newVideos = mutableListOf<NotificationHelper.NewVideoEntry>()
                subscriptions.chunked(CHANNEL_CHUNK_SIZE).forEach { chunk ->
                    coroutineScope {
                        chunk
                            .map { subscription ->
                                async {
                                    try {
                                        checkChannel(subscription, subscriptionRepository, announceReels, notifyShorts)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error checking channel ${subscription.channelName}", e)
                                        emptyList()
                                    }
                                }
                            }.awaitAll()
                            .flatten()
                            .let { newVideos.addAll(it) }
                    }
                }

                // A collaboration two followed channels both list must still notify once.
                val uniqueVideos = newVideos.distinctBy { it.videoId }
                if (uniqueVideos.isNotEmpty()) {
                    NotificationHelper.showSubscriptionUpdates(applicationContext, uniqueVideos)
                }

                Log.d(TAG, "Subscription check complete. Found ${newVideos.size} new videos.")
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Error during subscription check", e)
                Result.retry()
            }
        }

    private suspend fun checkChannel(
        subscription: ChannelSubscription,
        repository: SubscriptionRepository,
        announceReels: Boolean,
        notifyShorts: Boolean,
    ): List<NotificationHelper.NewVideoEntry> {
        val feed =
            dependencies.channelRssClient().fetch(subscription.channelId).getOrElse { error ->
                Log.w(TAG, "Failed to check RSS for ${subscription.channelName}: ${error.message}")
                return emptyList()
            }

        val latestVideo = feed.entries.firstOrNull() ?: return emptyList()
        if (subscription.lastVideoId == latestVideo.videoId) return emptyList()

        val linkedReelIds = feed.entries.filter { it.isShort == true }.mapTo(HashSet()) { it.videoId }
        val reelVideoIds =
            if (feed.entries.all { it.isShort != null }) {
                linkedReelIds
            } else {
                linkedReelIds + dependencies.channelReelIndex().reelIds(subscription.channelId).orEmpty()
            }

        // The channel moved on, so hand the whole page to the feed cache. Rows land without a
        // duration — the feed's on-demand enrichment fills that in, and the next full refresh of
        // this channel replaces them outright. The reel verdict cannot wait that long: an unmarked
        // reel is visible in the feed in the meantime, Shorts switched off or not.
        dependencies.subscriptionFeedRepository().seedFromNotificationCheck(
            channelId = subscription.channelId,
            channelName = feed.channelName ?: subscription.channelName,
            entries = feed.entries,
            reelVideoIds = reelVideoIds,
        )

        val newEntries = ChannelRssParser.newEntriesSince(feed.entries, subscription.lastVideoId)
        repository.updateChannelLatestVideo(subscription.channelId, latestVideo.videoId)

        if (newEntries.isNotEmpty()) {
            Log.d(TAG, "${newEntries.size} new video(s) for ${subscription.channelName}")
        }

        return newEntries
            .filter {
                SubscriptionNotificationPolicy.shouldAnnounce(
                    videoId = it.videoId,
                    reelVideoIds = reelVideoIds,
                    announceReelsInFeed = announceReels,
                    notifyShorts = notifyShorts,
                )
            }.map { video ->
                NotificationHelper.NewVideoEntry(
                    channelName = subscription.channelName,
                    videoTitle = video.title,
                    videoId = video.videoId,
                    thumbnailUrl = video.thumbnailUrl,
                )
            }
    }
}
