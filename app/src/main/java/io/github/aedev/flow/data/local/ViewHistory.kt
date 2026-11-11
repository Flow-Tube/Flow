package io.github.aedev.flow.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.aedev.flow.data.local.dao.WatchHistoryDao
import io.github.aedev.flow.data.local.dao.WatchProgress
import io.github.aedev.flow.data.local.entity.WatchHistoryEntity
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.profile.StoreScope
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Legacy DataStore kept only for one-time migration of existing user data.
 * After migration it is left empty (cleared).
 */
private val Context.viewHistoryDataStore: DataStore<Preferences> by safePreferencesDataStore(
    name = "view_history",
    scope = StoreScope.PROFILE_LIBRARY,
)

/**
 * Watch-history repository backed by Room SQLite.
*/
class ViewHistory private constructor(
    private val context: Context,
) {
    private val database = AppDatabase.profile(context)
    private val dao = database.watchHistoryDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "ViewHistory"
        private const val WATCH_HISTORY_TABLE = "watch_history"

        // Below SQLite's bound-variable limit on every Android version.
        private const val UNPLAYED_DELETE_BATCH = 500

        @Volatile
        private var instance: ViewHistory? = null

        fun getInstance(context: Context): ViewHistory =
            instance ?: synchronized(this) {
                instance ?: ViewHistory(context.applicationContext).also { created ->
                    instance = created
                    created.scope.launch { created.migrateFromDataStoreIfNeeded() }
                }
            }

        private fun positionKey(id: String) = longPreferencesKey("video_${id}_position")

        private fun durationKey(id: String) = longPreferencesKey("video_${id}_duration")

        private fun timestampKey(id: String) = longPreferencesKey("video_${id}_timestamp")

        private fun titleKey(id: String) = stringPreferencesKey("video_${id}_title")

        private fun thumbnailKey(id: String) = stringPreferencesKey("video_${id}_thumbnail")

        private fun channelNameKey(id: String) = stringPreferencesKey("video_${id}_channel_name")

        private fun channelIdKey(id: String) = stringPreferencesKey("video_${id}_channel_id")

        private fun isMusicKey(id: String) = booleanPreferencesKey("video_${id}_is_music")
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    /**
     * Save / update playback position (called during real playback).
     * REPLACE strategy so progress is always current.
     */
    suspend fun savePlaybackPosition(
        videoId: String,
        position: Long,
        duration: Long,
        title: String = "",
        thumbnailUrl: String = "",
        channelName: String = "",
        channelId: String = "",
        isMusic: Boolean = false,
        isShort: Boolean = false,
        // A device file's row stays local whichever caller writes it, or it would join the online
        // history, the engine's signals and sync under an id that names no YouTube video.
        isLocal: Boolean = LocalMediaIds.isLocal(videoId),
    ) {
        if (PlayerPreferences(context).isWatchHistorySavingBlocked()) return

        val thumbnail = if (isLocal) thumbnailUrl else ThumbnailUrlResolver.normalizeVideoThumbnail(videoId, thumbnailUrl)
        dao.upsert(
            WatchHistoryEntity(
                videoId = videoId,
                position = position,
                duration = duration,
                timestamp = System.currentTimeMillis(),
                title = title,
                thumbnailUrl = thumbnail,
                channelName = channelName,
                channelId = channelId,
                isMusic = isMusic,
                isShort = isShort,
                isLocal = isLocal,
            ),
        )
    }

    /**
     * Records that [videoId] played to its end. Its card then shows a full bar until the next
     * playback of it starts over from the beginning and saves new progress.
     */
    suspend fun markCompleted(
        videoId: String,
        durationMs: Long,
    ) {
        if (durationMs <= 0L) return
        if (PlayerPreferences(context).isWatchHistorySavingBlocked()) return
        dao.markCompleted(videoId, durationMs)
    }

    /** Clears playback progress for [videoId]. The history row stays; the card stops reading as watched. */
    suspend fun clearProgress(videoId: String) {
        dao.clearProgress(videoId)
    }

    suspend fun getSavedPosition(videoId: String): Long = dao.getPosition(videoId) ?: 0L

    suspend fun getSavedDuration(videoId: String): Long = dao.getDuration(videoId) ?: 0L

    /** Records that a song started without moving the place a long track was left at. */
    suspend fun touchMusicEntry(
        videoId: String,
        duration: Long,
        title: String,
        thumbnailUrl: String,
        channelName: String,
        channelId: String,
        isLocal: Boolean = LocalMediaIds.isLocal(videoId),
    ) = savePlaybackPosition(
        videoId = videoId,
        position = dao.getPosition(videoId) ?: 0L,
        duration = duration,
        title = title,
        thumbnailUrl = thumbnailUrl,
        channelName = channelName,
        channelId = channelId,
        isMusic = true,
        isLocal = isLocal,
    )

    /** Where a long track stopped. A row history already holds keeps its title, artist and time. */
    suspend fun saveMusicPosition(
        videoId: String,
        position: Long,
        duration: Long,
        title: String,
        thumbnailUrl: String,
        channelName: String,
    ) {
        if (PlayerPreferences(context).isWatchHistorySavingBlocked()) return
        if (dao.updateProgress(videoId, position, duration) > 0) return
        savePlaybackPosition(videoId, position, duration, title, thumbnailUrl, channelName, isMusic = true)
    }

    /**
     * Create-or-touch a history entry **without** overwriting an already-saved
     * playback position.  Call this on video open so the video appears in history
     * even if the user closes it immediately, while leaving any real progress intact.
     */
    suspend fun touchHistoryEntry(
        videoId: String,
        title: String = "",
        thumbnailUrl: String = "",
        channelName: String = "",
        channelId: String = "",
        duration: Long = 0L,
        isShort: Boolean = false,
    ) {
        if (PlayerPreferences(context).isWatchHistorySavingBlocked()) return

        val thumbnail = ThumbnailUrlResolver.normalizeVideoThumbnail(videoId, thumbnailUrl)
        val existingPosition = dao.getPosition(videoId) ?: 0L // preserve saved progress
        // Opening a video before its stream resolves passes no duration, and taking that 0 would
        // erase the length a real playback save had recorded — leaving a row with a position but
        // nothing to measure it against, which is exactly what drops it from the progress map.
        val resolvedDuration = duration.takeIf { it > 0 } ?: dao.getDuration(videoId) ?: 0L
        // A player opened from only an id knows no title yet; a blank must not erase the one on record.
        val existing = if (title.isBlank() || channelName.isBlank() || channelId.isBlank()) dao.getEntry(videoId).first() else null
        dao.upsert(
            WatchHistoryEntity(
                videoId = videoId,
                position = existingPosition,
                duration = resolvedDuration,
                timestamp = System.currentTimeMillis(),
                title = title.ifBlank { existing?.title.orEmpty() },
                thumbnailUrl = thumbnail,
                channelName = channelName.ifBlank { existing?.channelName.orEmpty() },
                channelId = channelId.ifBlank { existing?.channelId.orEmpty() },
                isMusic = false,
                isShort = isShort,
                isLocal = LocalMediaIds.isLocal(videoId),
            ),
        )
    }

    /**
     * Bulk-insert history entries from an import.
     * Uses IGNORE conflict strategy so that real playback progress already in
     * the DB is never overwritten by imported stubs.
     */
    suspend fun bulkSaveHistoryEntries(entries: List<VideoHistoryEntry>) {
        if (entries.isEmpty()) return
        val entities =
            entries.map { entry ->
                WatchHistoryEntity(
                    videoId = entry.videoId,
                    position = entry.position,
                    duration = entry.duration,
                    timestamp = entry.timestamp,
                    title = entry.title,
                    thumbnailUrl = ThumbnailUrlResolver.normalizeVideoThumbnail(entry.videoId, entry.thumbnailUrl),
                    channelName = entry.channelName,
                    channelId = entry.channelId,
                    isMusic = entry.isMusic,
                    isShort = entry.isShort,
                )
            }
        dao.insertAll(entities)
    }

    suspend fun clearVideoHistory(videoId: String) {
        dao.deleteEntry(videoId)
    }

    /** Removes [videoIds] from history where they were only imported, never played; returns how many went. */
    suspend fun removeUnplayedEntries(videoIds: Collection<String>): Int =
        videoIds.chunked(UNPLAYED_DELETE_BATCH).sumOf { dao.deleteUnplayed(it) }

    /**
     * Marks the given video as fully watched (position = duration) so it no longer
     * appears in the continue-watching mini-player popup on the next app launch.
     */
    suspend fun markAsWatched(videoId: String) {
        dao.markAsWatched(videoId)
    }

    suspend fun clearAllHistory() {
        dao.clearAll()
    }

    suspend fun clearShortsHistory() {
        dao.clearShorts()
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    fun getPlaybackPosition(videoId: String): Flow<Long> = dao.getEntry(videoId).map { it?.position ?: 0L }

    fun getVideoHistory(videoId: String): Flow<VideoHistoryEntry?> = dao.getEntry(videoId).map { it?.toDomain() }

    /** All history, newest first. */
    fun getAllHistory(): Flow<List<VideoHistoryEntry>> = observeHistory()

    fun getRecentLibraryHistory(limit: Int): Flow<List<VideoHistoryEntry>> =
        dao.getRecentLibraryHistory(limit).map { list -> list.map { it.toDomain() } }

    /** Video (non-music) history, newest first. */
    fun getVideoHistoryFlow(): Flow<List<VideoHistoryEntry>> = observeHistory(isMusic = 0, isLocal = 0)

    /** Music history, newest first. */
    fun getMusicHistoryFlow(): Flow<List<VideoHistoryEntry>> = observeHistory(isMusic = 1, isLocal = 0)

    /** Plays of files on the device, newest first: their progress and when they were last played. */
    fun getLocalHistoryFlow(): Flow<List<VideoHistoryEntry>> = observeHistory(isLocal = 1)

    /** Position and duration of every entry, for progress bars. */
    fun getAllWatchProgress(): Flow<List<WatchProgress>> = observe { dao.readProgress() }

    /** Position and duration of every video (non-music, non-local) entry, for watched checks. */
    fun getVideoWatchProgress(): Flow<List<WatchProgress>> = observe { dao.readProgress(isMusic = 0, isLocal = 0) }

    /** The latest [limit] video entries, newest first, without reading the rest of the table. */
    suspend fun getRecentVideoHistory(
        limit: Int,
        includeShorts: Boolean,
    ): List<VideoHistoryEntry> = dao.getRecentVideoHistory(limit, includeShorts).map { it.toDomain() }

    /** [getRecentVideoHistory], read again whenever the history table changes. */
    fun observeRecentVideoHistory(
        limit: Int,
        includeShorts: Boolean,
    ): Flow<List<VideoHistoryEntry>> = observe { getRecentVideoHistory(limit, includeShorts) }

    private fun observeHistory(
        isMusic: Int = WatchHistoryDao.ANY,
        isLocal: Int = WatchHistoryDao.ANY,
    ): Flow<List<VideoHistoryEntry>> = observe { dao.readHistory(isMusic, isLocal).map { it.toDomain() } }

    /** Re-reads on every change to the table, as a Room Flow query would, dropping a read a newer change supersedes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> observe(read: suspend () -> T): Flow<T> =
        database.invalidationTracker
            .createFlow(WATCH_HISTORY_TABLE)
            .mapLatest { read() }
            .flowOn(Dispatchers.IO)

    suspend fun getShortProgress(): List<WatchProgress> = dao.readShortProgress()

    suspend fun getWatchProgress(videoId: String): WatchProgress? = dao.getProgress(videoId)

    /** Efficient count without loading all rows — use this instead of list.size. */
    fun getVideoCount(): Flow<Int> = dao.getVideoCount()

    fun getLibraryHistoryCount(): Flow<Int> = dao.getLibraryHistoryCount()

    /**
     * Returns the most recently watched unfinished video (<95% complete).
     * Used to restore the "resume" mini player on app launch.
     */
    suspend fun getLatestUnfinishedVideo() = dao.getLatestUnfinishedVideo()

    /**
     * On the first launch after the 10→11 DB migration the Room table is empty
     * but the old DataStore may contain thousands of entries.  Read them all
     * in batches and insert into Room, then clear the DataStore.
     */
    private suspend fun migrateFromDataStoreIfNeeded() {
        try {
            val roomCount = dao.getCount().first()
            if (roomCount > 0) return

            val prefs = context.viewHistoryDataStore.data.first()
            val videoIds = mutableSetOf<String>()
            prefs.asMap().keys.forEach { key ->
                val name = key.name
                if (name.startsWith("video_") && name.endsWith("_position")) {
                    videoIds.add(name.removePrefix("video_").removeSuffix("_position"))
                }
            }
            if (videoIds.isEmpty()) return

            Log.i(TAG, "Migrating ${videoIds.size} watch-history entries from DataStore → Room")

            val batchSize = 500
            val batch = mutableListOf<WatchHistoryEntity>()

            for (videoId in videoIds) {
                val position = prefs[positionKey(videoId)] ?: continue
                val duration = prefs[durationKey(videoId)] ?: 0L
                val ts = prefs[timestampKey(videoId)] ?: 0L
                val title = prefs[titleKey(videoId)] ?: ""
                val thumb = ThumbnailUrlResolver.normalizeVideoThumbnail(videoId, prefs[thumbnailKey(videoId)])
                val chName = prefs[channelNameKey(videoId)] ?: ""
                val chId = prefs[channelIdKey(videoId)] ?: ""
                val isMusic = prefs[isMusicKey(videoId)] ?: false

                batch += WatchHistoryEntity(videoId, position, duration, ts, title, thumb, chName, chId, isMusic)

                if (batch.size >= batchSize) {
                    dao.insertAll(batch)
                    batch.clear()
                    yield()
                }
            }
            if (batch.isNotEmpty()) dao.insertAll(batch)

            context.viewHistoryDataStore.edit { it.clear() }
            Log.i(TAG, "DataStore → Room migration complete (${videoIds.size} entries)")
        } catch (e: Exception) {
            Log.e(TAG, "Migration from DataStore failed", e)
        }
    }
}

data class VideoHistoryEntry(
    val videoId: String,
    val position: Long,
    val duration: Long,
    val timestamp: Long,
    val title: String,
    val thumbnailUrl: String,
    val channelName: String = "",
    val channelId: String = "",
    val isMusic: Boolean = false,
    val isShort: Boolean = false,
    val isLocal: Boolean = false,
) {
    val progressPercentage: Float
        get() = if (duration > 0) (position.toFloat() / duration.toFloat()) * 100f else 0f
}
