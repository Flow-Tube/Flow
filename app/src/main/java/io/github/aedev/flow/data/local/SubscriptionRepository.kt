package io.github.aedev.flow.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.aedev.flow.data.engagement.FeedInvalidationBus
import io.github.aedev.flow.data.local.AppDatabase
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.subscriptionsDataStore: DataStore<Preferences> by safePreferencesDataStore(name = "subscriptions")

class SubscriptionRepository private constructor(
    private val context: Context,
) {
    companion object {
        @Volatile
        private var instance: SubscriptionRepository? = null

        fun getInstance(context: Context): SubscriptionRepository =
            instance ?: synchronized(this) {
                instance ?: SubscriptionRepository(context.applicationContext).also { instance = it }
            }

        // Keys format: "channel_{channelId}" -> JSON string with channel info
        private fun channelKey(channelId: String) = stringPreferencesKey("channel_$channelId")

        /** "unsub_{channelId}" -> epoch ms the user unsubscribed. See [unsubscribedTombstones]. */
        private fun unsubscribedKey(channelId: String) = stringPreferencesKey("unsub_$channelId")

        private const val UNSUBSCRIBED_PREFIX = "unsub_"

        private const val SUBSCRIPTIONS_ORDER_KEY = "subscriptions_order"

        /**
         * How long an unsubscribe is remembered. Device sync resolves subscribe-vs-unsubscribe by
         * comparing their timestamps, so forgetting a tombstone lets a peer that still holds the
         * subscription resurrect it. Long enough that any realistic sync gap is covered, bounded so
         * the store cannot grow without limit.
         */
        private const val UNSUBSCRIBE_TOMBSTONE_RETENTION_MS = 365L * 24 * 60 * 60 * 1000
    }

    /**
     * Subscribe to a channel
     */
    suspend fun subscribe(channel: ChannelSubscription) {
        context.subscriptionsDataStore.edit { preferences ->
            val safeChannel = channel.withPreservedThumbnail(preferences)

            // Save channel data
            preferences[channelKey(safeChannel.channelId)] = serializeChannel(safeChannel)
            preferences.remove(unsubscribedKey(safeChannel.channelId)) // re-subscribing clears the tombstone

            // Update order list
            val currentOrder = preferences[stringPreferencesKey(SUBSCRIPTIONS_ORDER_KEY)] ?: ""
            val orderList =
                if (currentOrder.isEmpty()) {
                    mutableListOf()
                } else {
                    currentOrder.split(",").toMutableList()
                }

            if (!orderList.contains(safeChannel.channelId)) {
                orderList.add(0, safeChannel.channelId)
                preferences[stringPreferencesKey(SUBSCRIPTIONS_ORDER_KEY)] = orderList.joinToString(",")
            }
        }
    }

    suspend fun subscribeAll(channels: Collection<ChannelSubscription>) {
        if (channels.isEmpty()) return

        context.subscriptionsDataStore.edit { preferences ->
            val currentOrder =
                preferences[stringPreferencesKey(SUBSCRIPTIONS_ORDER_KEY)]
                    .orEmpty()
                    .split(",")
                    .filter { it.isNotEmpty() }
            val knownIds = currentOrder.toMutableSet()
            val newIds = mutableListOf<String>()

            channels.forEach { channel ->
                val safeChannel = channel.withPreservedThumbnail(preferences)
                preferences[channelKey(safeChannel.channelId)] = serializeChannel(safeChannel)
                preferences.remove(unsubscribedKey(safeChannel.channelId))
                if (knownIds.add(safeChannel.channelId)) {
                    newIds += safeChannel.channelId
                }
            }

            if (newIds.isNotEmpty()) {
                preferences[stringPreferencesKey(SUBSCRIPTIONS_ORDER_KEY)] =
                    (newIds.asReversed() + currentOrder).joinToString(",")
            }
        }
    }

    /**
     * Subscribe to [channels] a single time per [seedKey]. Channels already subscribed, or that the
     * user has unsubscribed from, are left alone, and once the flag is set the user's later
     * unsubscribes are never undone. Returns how many were added.
     */
    suspend fun seedOnce(
        seedKey: String,
        channels: Collection<ChannelSubscription>,
    ): Int {
        var added = 0
        context.subscriptionsDataStore.edit { preferences ->
            val flag = stringPreferencesKey("seeded_$seedKey")
            if (preferences[flag] != null) return@edit

            val currentOrder =
                preferences[stringPreferencesKey(SUBSCRIPTIONS_ORDER_KEY)]
                    .orEmpty()
                    .split(",")
                    .filter { it.isNotEmpty() }
            val knownIds = currentOrder.toMutableSet()
            val newIds = mutableListOf<String>()

            channels.forEach { channel ->
                if (preferences.contains(channelKey(channel.channelId))) return@forEach
                if (preferences.contains(unsubscribedKey(channel.channelId))) return@forEach
                preferences[channelKey(channel.channelId)] = serializeChannel(channel)
                if (knownIds.add(channel.channelId)) newIds += channel.channelId
            }

            if (newIds.isNotEmpty()) {
                preferences[stringPreferencesKey(SUBSCRIPTIONS_ORDER_KEY)] =
                    (newIds.asReversed() + currentOrder).joinToString(",")
            }
            preferences[flag] = System.currentTimeMillis().toString()
            added = newIds.size
        }
        return added
    }

    /**
     * Unsubscribe from a channel
     */
    suspend fun unsubscribe(channelId: String) {
        val now = System.currentTimeMillis()
        context.subscriptionsDataStore.edit { preferences ->
            preferences.remove(channelKey(channelId))
            // Remember *when*, so device sync can tell an unsubscribe from "this device never knew
            // about the channel" and the removal actually reaches the other device.
            preferences[unsubscribedKey(channelId)] = now.toString()
            prune(preferences, now)

            // Update order list
            val currentOrder = preferences[stringPreferencesKey(SUBSCRIPTIONS_ORDER_KEY)] ?: ""
            if (currentOrder.isNotEmpty()) {
                val orderList = currentOrder.split(",").toMutableList()
                orderList.remove(channelId)
                preferences[stringPreferencesKey(SUBSCRIPTIONS_ORDER_KEY)] = orderList.joinToString(",")
            }
        }

        AppDatabase
            .getDatabase(context)
            .cacheDao()
            .deleteSubscriptionFeedForChannel(channelId)
        FeedInvalidationBus.emit(FeedInvalidationBus.Event.ChannelUnsubscribed(channelId))
    }

    /**
     * Check if subscribed to a channel
     */
    fun isSubscribed(channelId: String): Flow<Boolean> =
        context.subscriptionsDataStore.data.map { preferences ->
            preferences.contains(channelKey(channelId))
        }

    /**
     * Get all subscriptions
     */
    fun getAllSubscriptions(): Flow<List<ChannelSubscription>> =
        context.subscriptionsDataStore.data.map { preferences ->
            val orderString = preferences[stringPreferencesKey(SUBSCRIPTIONS_ORDER_KEY)] ?: ""
            if (orderString.isEmpty()) {
                emptyList()
            } else {
                val orderList = orderString.split(",")
                orderList.mapNotNull { channelId ->
                    val channelData = preferences[channelKey(channelId)]
                    channelData?.let { deserializeChannel(it) }
                }
            }
        }

    /**
     * Get all subscription IDs as a Set
     */
    suspend fun getAllSubscriptionIds(): Set<String> {
        val orderString =
            context.subscriptionsDataStore.data
                .map { preferences ->
                    preferences[stringPreferencesKey(SUBSCRIPTIONS_ORDER_KEY)] ?: ""
                }.first()

        return if (orderString.isEmpty()) {
            emptySet()
        } else {
            orderString.split(",").toSet()
        }
    }

    /**
     * Channels this device has explicitly unsubscribed from, mapped to when it happened.
     *
     * Device sync ships these as tombstones. Without them an unsubscribe is indistinguishable from
     * "never subscribed", and the peer that still holds the channel puts it straight back.
     */
    suspend fun unsubscribedTombstones(): Map<String, Long> =
        context.subscriptionsDataStore.data
            .map { preferences ->
                preferences
                    .asMap()
                    .mapNotNull { (key, value) ->
                        if (!key.name.startsWith(UNSUBSCRIBED_PREFIX)) return@mapNotNull null
                        val at = (value as? String)?.toLongOrNull() ?: return@mapNotNull null
                        key.name.removePrefix(UNSUBSCRIBED_PREFIX) to at
                    }.toMap()
            }.first()

    /** Record a peer's unsubscribe so it keeps propagating to any third device. */
    suspend fun recordUnsubscribedAt(tombstones: Map<String, Long>) {
        if (tombstones.isEmpty()) return
        val now = System.currentTimeMillis()
        context.subscriptionsDataStore.edit { preferences ->
            tombstones.forEach { (channelId, at) ->
                val existing = preferences[unsubscribedKey(channelId)]?.toLongOrNull() ?: 0L
                if (at > existing) preferences[unsubscribedKey(channelId)] = at.toString()
            }
            prune(preferences, now)
        }
    }

    /** Drop tombstones past the retention window so the store cannot grow without limit. */
    private fun prune(
        preferences: MutablePreferences,
        now: Long,
    ) {
        val cutoff = now - UNSUBSCRIBE_TOMBSTONE_RETENTION_MS
        preferences
            .asMap()
            .keys
            .filter { it.name.startsWith(UNSUBSCRIBED_PREFIX) }
            .forEach { key ->
                val at = (preferences[key] as? String)?.toLongOrNull()
                if (at == null || at < cutoff) preferences.remove(key)
            }
    }

    /**
     * Get subscription by channel ID
     */
    fun getSubscription(channelId: String): Flow<ChannelSubscription?> =
        context.subscriptionsDataStore.data.map { preferences ->
            val channelData = preferences[channelKey(channelId)]
            channelData?.let { deserializeChannel(it) }
        }

    suspend fun repairVideoThumbnailSubscriptions(fetchChannelThumbnail: suspend (String) -> String): Int {
        val subscriptions = getAllSubscriptions().first()
        val repairs =
            subscriptions
                .filter {
                    it.channelThumbnail.isBlank() ||
                        ThumbnailUrlResolver.isUnusableChannelAvatar(it.channelThumbnail)
                }
                .mapNotNull { subscription ->
                    val avatar = fetchChannelThumbnail(subscription.channelId).trim()
                    if (avatar.isNotEmpty() && !ThumbnailUrlResolver.isUnusableChannelAvatar(avatar)) {
                        subscription.channelId to subscription.copy(channelThumbnail = avatar)
                    } else {
                        null
                    }
                }.toMap()

        if (repairs.isEmpty()) return 0

        context.subscriptionsDataStore.edit { preferences ->
            repairs.forEach { (channelId, subscription) ->
                preferences[channelKey(channelId)] = serializeChannel(subscription)
            }
        }
        return repairs.size
    }

    private fun serializeChannel(channel: ChannelSubscription): String = SubscriptionRecordCodec.encode(channel)

    private fun ChannelSubscription.withPreservedThumbnail(preferences: Preferences): ChannelSubscription {
        val existing = preferences[channelKey(channelId)]?.let { deserializeChannel(it) }
        return if (
            ThumbnailUrlResolver.isUnusableChannelAvatar(channelThumbnail) &&
            existing?.channelThumbnail?.isNotBlank() == true &&
            !ThumbnailUrlResolver.isUnusableChannelAvatar(existing.channelThumbnail)
        ) {
            copy(channelThumbnail = existing.channelThumbnail)
        } else {
            this
        }
    }

    private fun deserializeChannel(data: String): ChannelSubscription? = SubscriptionRecordCodec.decode(data)

    /**
     * Update the notification state for a channel
     */
    suspend fun updateNotificationState(
        channelId: String,
        enabled: Boolean,
    ) {
        context.subscriptionsDataStore.edit { preferences ->
            val channelData = preferences[channelKey(channelId)]
            if (channelData != null) {
                val subscription = deserializeChannel(channelData)
                if (subscription != null) {
                    val updated = subscription.copy(isNotificationEnabled = enabled)
                    preferences[channelKey(channelId)] = serializeChannel(updated)
                }
            }
        }
    }

    /**
     * Record that the subscription feed has just fetched these channels.
     *
     * Written in one [DataStore] transaction so a refresh over hundreds of channels does not
     * produce hundreds of preference commits.
     */
    suspend fun markFeedFetched(
        channelIds: Collection<String>,
        fetchedAt: Long,
    ) {
        if (channelIds.isEmpty()) return

        context.subscriptionsDataStore.edit { preferences ->
            channelIds.forEach { channelId ->
                val subscription = preferences[channelKey(channelId)]?.let { deserializeChannel(it) }
                if (subscription != null) {
                    preferences[channelKey(channelId)] =
                        serializeChannel(subscription.copy(lastFeedFetchAt = fetchedAt))
                }
            }
        }
    }

    /**
     * Update the last seen video for a channel
     */
    suspend fun updateChannelLatestVideo(
        channelId: String,
        videoId: String,
    ) {
        context.subscriptionsDataStore.edit { preferences ->
            val channelData = preferences[channelKey(channelId)]
            if (channelData != null) {
                val subscription = deserializeChannel(channelData)
                if (subscription != null) {
                    val updated =
                        subscription.copy(
                            lastVideoId = videoId,
                            lastCheckTime = System.currentTimeMillis(),
                        )
                    preferences[channelKey(channelId)] = serializeChannel(updated)
                }
            }
        }
    }
}

data class ChannelSubscription(
    val channelId: String,
    val channelName: String,
    val channelThumbnail: String,
    val subscribedAt: Long = System.currentTimeMillis(),
    val lastVideoId: String? = null,
    /** When the background new-upload check last saw a *new* video for this channel. */
    val lastCheckTime: Long = 0L,
    val isNotificationEnabled: Boolean = false,
    val isMusic: Boolean = false,
    /** When the subscription feed last fetched this channel; 0 means never. */
    val lastFeedFetchAt: Long = 0L,
)
