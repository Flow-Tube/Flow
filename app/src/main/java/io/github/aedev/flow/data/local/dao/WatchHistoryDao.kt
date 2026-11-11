package io.github.aedev.flow.data.local.dao

import androidx.room.*
import io.github.aedev.flow.data.local.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchHistoryDao {
    // ── Writes ──────────────────────────────────────────────────────────────

    /** Save / update a single entry (e.g. real-time playback position). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WatchHistoryEntity)

    /**
     * Bulk insert many entries at once.
     * Uses IGNORE so that actual watch-progress records already in the DB are
     * never overwritten by imported stubs.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<WatchHistoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<WatchHistoryEntity>)

    @Query("DELETE FROM watch_history WHERE videoId = :videoId")
    suspend fun deleteEntry(videoId: String)

    @Query("DELETE FROM watch_history")
    suspend fun clearAll()

    /** Deletes the rows for [videoIds] that were never played in Flow: no position and no duration. */
    @Query("DELETE FROM watch_history WHERE videoId IN (:videoIds) AND position = 0 AND duration = 0")
    suspend fun deleteUnplayed(videoIds: List<String>): Int

    @Query("DELETE FROM watch_history WHERE isShort = 1")
    suspend fun clearShorts()

    // ── Reads ────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM watch_history WHERE isShort = 0 AND isLocal = 0 ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLibraryHistory(limit: Int): Flow<List<WatchHistoryEntity>>

    /**
     * One keyset page, newest first. [isMusic] and [isLocal] take 0 or 1, or [ANY]. Whole-table reads
     * go through these pages: a single cursor over a long history outgrows its CursorWindow and
     * fails on the refill (#1054).
     */
    @Query(
        """
        SELECT * FROM watch_history
        WHERE (:isMusic < 0 OR isMusic = :isMusic)
        AND (:isLocal < 0 OR isLocal = :isLocal)
        AND (timestamp < :beforeTimestamp OR (timestamp = :beforeTimestamp AND videoId > :afterVideoId))
        ORDER BY timestamp DESC, videoId ASC
        LIMIT :limit
    """,
    )
    suspend fun getHistoryPage(
        isMusic: Int,
        isLocal: Int,
        beforeTimestamp: Long,
        afterVideoId: String,
        limit: Int,
    ): List<WatchHistoryEntity>

    /** [getHistoryPage] with only what watched and progress checks read. */
    @Query(
        """
        SELECT videoId, position, duration, timestamp FROM watch_history
        WHERE (:isMusic < 0 OR isMusic = :isMusic)
        AND (:isLocal < 0 OR isLocal = :isLocal)
        AND (timestamp < :beforeTimestamp OR (timestamp = :beforeTimestamp AND videoId > :afterVideoId))
        ORDER BY timestamp DESC, videoId ASC
        LIMIT :limit
    """,
    )
    suspend fun getProgressPage(
        isMusic: Int,
        isLocal: Int,
        beforeTimestamp: Long,
        afterVideoId: String,
        limit: Int,
    ): List<WatchProgress>

    @Query(
        "SELECT * FROM watch_history WHERE isMusic = 0 AND isLocal = 0 AND (:includeShorts OR isShort = 0) " +
            "ORDER BY timestamp DESC LIMIT :limit",
    )
    suspend fun getRecentVideoHistory(
        limit: Int,
        includeShorts: Boolean,
    ): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE videoId = :videoId")
    fun getEntry(videoId: String): Flow<WatchHistoryEntity?>

    @Query("SELECT channelId, COUNT(*) AS watched FROM watch_history WHERE isLocal = 0 AND channelId != '' GROUP BY channelId")
    suspend fun watchedCountByChannel(): List<ChannelWatchCount>

    /** Only touches a row that already exists, so a finished video keeps its title and timestamp. */
    @Query("UPDATE watch_history SET position = :durationMs, duration = :durationMs WHERE videoId = :videoId")
    suspend fun markCompleted(
        videoId: String,
        durationMs: Long,
    )

    /** Moves only the saved place; 0 means [videoId] has no row yet. */
    @Query("UPDATE watch_history SET position = :position, duration = :duration WHERE videoId = :videoId")
    suspend fun updateProgress(
        videoId: String,
        position: Long,
        duration: Long,
    ): Int

    /** Drops saved progress without deleting the history row, so the card no longer reads as watched. */
    @Query("UPDATE watch_history SET position = 0 WHERE videoId = :videoId")
    suspend fun clearProgress(videoId: String)

    @Query("SELECT position FROM watch_history WHERE videoId = :videoId")
    suspend fun getPosition(videoId: String): Long?

    @Query("SELECT duration FROM watch_history WHERE videoId = :videoId")
    suspend fun getDuration(videoId: String): Long?

    @Query("SELECT COUNT(*) FROM watch_history")
    fun getCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM watch_history WHERE isMusic = 0 AND isLocal = 0")
    fun getVideoCount(): Flow<Int>

    /** Every channel with a video in history, seeding the recap ledger so they never count as new. */
    @Query("SELECT DISTINCT channelId FROM watch_history WHERE isMusic = 0 AND isLocal = 0 AND channelId != ''")
    suspend fun getWatchedChannelIds(): List<String>

    /** Distinct videos per channel, the only per-channel count history can honestly give. */
    @Query(
        "SELECT channelId, MAX(channelName) AS channelName, COUNT(*) AS videos FROM watch_history " +
            "WHERE isMusic = 0 AND isLocal = 0 AND channelId != '' GROUP BY channelId ORDER BY videos DESC LIMIT :limit",
    )
    suspend fun getChannelVideoCounts(limit: Int): List<ChannelVideoCount>

    /** Counts exactly the rows [getRecentLibraryHistory] draws from, for the Library section row. */
    @Query("SELECT COUNT(*) FROM watch_history WHERE isShort = 0 AND isLocal = 0")
    fun getLibraryHistoryCount(): Flow<Int>

    /**
     * Returns video IDs that the user has already watched (position > 0 OR appeared in history).
     * Used to filter watched shorts from the subscription shelf. Local files are excluded so the
     * recommendation/feed engine never learns from them.
     */
    @Query("SELECT videoId FROM watch_history WHERE isMusic = 0 AND isLocal = 0")
    suspend fun getAllWatchedVideoIds(): List<String>

    /** Every Short with progress; which of them count as watched is [WatchedThreshold.isWatched]'s call. */
    @Query(
        "SELECT videoId, position, duration, timestamp FROM watch_history " +
            "WHERE isMusic = 0 AND isLocal = 0 AND isShort = 1 AND position > 0 AND duration > 0",
    )
    suspend fun readShortProgress(): List<WatchProgress>

    @Query("SELECT videoId, position, duration, timestamp FROM watch_history WHERE videoId = :videoId")
    suspend fun getProgress(videoId: String): WatchProgress?

    /** Progress for just [videoIds], so a search page asks about its own results and nothing else. */
    @Query(
        "SELECT videoId, position, duration, timestamp FROM watch_history " +
            "WHERE isMusic = 0 AND isLocal = 0 AND videoId IN (:videoIds)",
    )
    suspend fun getProgressFor(videoIds: List<String>): List<WatchProgress>

    /**
     * Returns the most recently watched non-music, non-Short video **only if that specific video
     * is still in progress**.  By restricting to the maximum timestamp we avoid the
     * "stack fallback" problem where finishing one video causes the previous unfinished
     * video to pop up in the continue-watching mini-player instead.
     *
     * Criteria:
     *  - Must be the absolute latest watched video (by timestamp)
     *  - position saved (> 0)
     *  - less than 95% watched
     *  - more than 30 seconds of content remaining
     */
    @Query(
        """
        SELECT * FROM watch_history
        WHERE isMusic = 0
        AND isShort = 0
        AND isLocal = 0
        AND duration > 0
        AND position > 0
        AND (CAST(position AS REAL) / CAST(duration AS REAL)) < 0.95
        AND (duration - position) > 30000
        AND timestamp = (SELECT MAX(timestamp) FROM watch_history WHERE isMusic = 0 AND isShort = 0 AND isLocal = 0)
        LIMIT 1
    """,
    )
    suspend fun getLatestUnfinishedVideo(): WatchHistoryEntity?

    /**
     * Marks a video as fully watched by setting position = duration.
     * This excludes it from the continue-watching popup on the next launch.
     * Called when the user explicitly dismisses the restored-session mini-player.
     */
    @Query("UPDATE watch_history SET position = duration WHERE videoId = :videoId")
    suspend fun markAsWatched(videoId: String)

    companion object {
        /** Matches either value in the page queries' 0/1 filters. */
        const val ANY = -1
    }
}

/** One row of [WatchHistoryDao.getProgressPage]. */
data class WatchProgress(
    val videoId: String,
    val position: Long,
    val duration: Long,
    val timestamp: Long,
)

/** One row of [WatchHistoryDao.getChannelVideoCounts]. */
data class ChannelVideoCount(
    val channelId: String,
    val channelName: String,
    val videos: Int,
)

/** How many videos of one channel are in watch history. */
data class ChannelWatchCount(
    val channelId: String,
    val watched: Int,
)
