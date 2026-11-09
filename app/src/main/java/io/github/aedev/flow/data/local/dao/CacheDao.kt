package io.github.aedev.flow.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.github.aedev.flow.data.local.entity.MusicHomeCacheEntity
import io.github.aedev.flow.data.local.entity.MusicHomeChipEntity
import io.github.aedev.flow.data.local.entity.SubscriptionFeedEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CacheDao {
    // Subscriptions
    @Query("SELECT * FROM subscription_feed_cache ORDER BY timestamp DESC LIMIT 1500")
    fun getSubscriptionFeed(): Flow<List<SubscriptionFeedEntity>>

    /** Returns how many rows are currently in the cache. */
    @Query("SELECT COUNT(*) FROM subscription_feed_cache")
    suspend fun getSubscriptionFeedCount(): Int

    /** Returns the most-recent cachedAt timestamp, or null when the table is empty. */
    @Query("SELECT MAX(cachedAt) FROM subscription_feed_cache")
    suspend fun getLatestCachedAt(): Long?

    /**
     * A subscription group's rows, newest first, together with the collaborations found through its
     * channels, so busy channels outside the group never push its rows past the feed's row limit.
     */
    @Query(
        "SELECT * FROM subscription_feed_cache WHERE channelId IN (:channelIds) OR feedChannelId IN (:channelIds) " +
            "ORDER BY timestamp DESC",
    )
    fun observeSubscriptionFeedForChannels(channelIds: List<String>): Flow<List<SubscriptionFeedEntity>>

    /** Every channel with uploads stored, so a full refresh can drop the ones no longer followed. */
    @Query("SELECT DISTINCT channelId FROM subscription_feed_cache WHERE feedChannelId = ''")
    suspend fun subscriptionUploadChannelIds(): List<String>

    /** Rows for the given channels only, so an incremental refresh can merge against them. */
    @Query("SELECT * FROM subscription_feed_cache WHERE channelId IN (:channelIds)")
    suspend fun getSubscriptionFeedForChannels(channelIds: List<String>): List<SubscriptionFeedEntity>

    @Query("SELECT * FROM subscription_feed_cache WHERE videoId = :videoId")
    suspend fun getSubscriptionFeedRow(videoId: String): SubscriptionFeedEntity?

    /** Each channel's newest stored upload of its own. */
    @Query("SELECT channelId, MAX(timestamp) AS latestUploadAt FROM subscription_feed_cache WHERE feedChannelId = '' GROUP BY channelId")
    suspend fun subscriptionLatestUploads(): List<ChannelLatestUploadRow>

    @Query("SELECT DISTINCT channelId, channelName FROM subscription_feed_cache WHERE channelId IN (:channelIds) AND channelName != ''")
    suspend fun subscriptionFeedChannelNames(channelIds: List<String>): List<ChannelNameRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscriptionFeed(videos: List<SubscriptionFeedEntity>)

    /**
     * Adds rows without touching ones that already exist, so the background new-upload check can
     * seed the feed without clobbering metadata the feed has since enriched.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSubscriptionFeedIfAbsent(videos: List<SubscriptionFeedEntity>)

    @Query(
        """
        UPDATE subscription_feed_cache
        SET title = :title,
            channelName = :channelName,
            channelId = :channelId,
            thumbnailUrl = :thumbnailUrl,
            duration = :duration,
            viewCount = :viewCount,
            isLive = :isLive,
            isUpcoming = :isUpcoming,
            uploadDate = :uploadDate,
            timestamp = :timestamp,
            timestampIsExact = :timestampIsExact
        WHERE videoId = :videoId
        """,
    )
    suspend fun updateSubscriptionFeedMetadata(
        videoId: String,
        title: String,
        channelName: String,
        channelId: String,
        thumbnailUrl: String,
        duration: Int,
        viewCount: Long,
        isLive: Boolean,
        isUpcoming: Boolean,
        uploadDate: String,
        timestamp: Long,
        timestampIsExact: Boolean,
    )

    /** Everything a channel brought into the feed: its uploads and its collaborations. */
    @Query("DELETE FROM subscription_feed_cache WHERE channelId = :channelId OR feedChannelId = :channelId")
    suspend fun deleteSubscriptionFeedForChannel(channelId: String)

    /**
     * The channels' own uploads, ahead of an incremental refresh; their collaborations follow their
     * own scan. Callers must chunk [channelIds] to stay under SQLite's bound-variable limit.
     */
    @Query("DELETE FROM subscription_feed_cache WHERE channelId IN (:channelIds) AND feedChannelId = ''")
    suspend fun deleteSubscriptionFeedForChannels(channelIds: List<String>)

    @Query("DELETE FROM subscription_feed_cache WHERE feedChannelId = :feedChannelId")
    suspend fun deleteCollaborationsFrom(feedChannelId: String)

    @Query("DELETE FROM subscription_feed_cache WHERE feedChannelId != ''")
    suspend fun deleteCollaborations()

    /**
     * Drops rows that have aged past the feed's lookback window. Upcoming items are kept because
     * their timestamp is a future premiere date, not an upload date.
     */
    @Query(
        """
        DELETE FROM subscription_feed_cache
        WHERE isUpcoming = 0 AND timestamp > 0 AND timestamp < :cutoffMillis
        """,
    )
    suspend fun pruneSubscriptionFeedOlderThan(cutoffMillis: Long)

    @Query("DELETE FROM subscription_feed_cache")
    suspend fun clearSubscriptionFeed()

    /** Swaps [feedChannelId]'s collaborations for [rows], leaving any video stored as an upload alone. */
    @Transaction
    suspend fun replaceCollaborations(
        feedChannelId: String,
        rows: List<SubscriptionFeedEntity>,
    ) {
        deleteCollaborationsFrom(feedChannelId)
        insertSubscriptionFeedIfAbsent(rows)
    }

    // Music
    @Query("SELECT * FROM music_home_cache ORDER BY orderBy ASC")
    fun getMusicHomeSections(): Flow<List<MusicHomeCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMusicHomeSections(sections: List<MusicHomeCacheEntity>)

    @Query("DELETE FROM music_home_cache")
    suspend fun clearMusicHomeCache()

    // Music Chips
    @Query("SELECT * FROM music_home_chips_cache ORDER BY orderBy ASC")
    fun getMusicHomeChips(): Flow<List<MusicHomeChipEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMusicHomeChips(chips: List<MusicHomeChipEntity>)

    @Query("DELETE FROM music_home_chips_cache")
    suspend fun clearMusicHomeChips()
}
