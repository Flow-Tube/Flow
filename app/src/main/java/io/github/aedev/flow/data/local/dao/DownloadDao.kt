package io.github.aedev.flow.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import io.github.aedev.flow.data.local.entity.DownloadEntity
import io.github.aedev.flow.data.local.entity.DownloadItemEntity
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.local.entity.DownloadWithItems
import kotlinx.coroutines.flow.Flow

/** One row of the download queue. */
data class QueuedDownload(
    val videoId: String,
    val createdAt: Long,
    val status: DownloadItemStatus,
)

@Dao
interface DownloadDao {
    // ===== Download (parent) =====

    // An upsert, never REPLACE: REPLACE deletes the row first, and the foreign key cascades that
    // delete to every file row of the download.
    @Upsert
    suspend fun insertDownload(download: DownloadEntity)

    /** Starts [download] over with [items], dropping the file rows of any earlier attempt. */
    @Transaction
    suspend fun replaceDownload(
        download: DownloadEntity,
        items: List<DownloadItemEntity>,
    ) {
        deleteItemsFor(download.videoId)
        insertDownload(download)
        insertItems(items)
    }

    @Query("DELETE FROM download_items WHERE videoId = :videoId")
    suspend fun deleteItemsFor(videoId: String)

    @Query("DELETE FROM downloads WHERE videoId = :videoId")
    suspend fun deleteDownload(videoId: String)

    @Query("SELECT * FROM downloads WHERE videoId = :videoId")
    suspend fun getDownloadByVideoId(videoId: String): DownloadEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM downloads WHERE videoId = :videoId)")
    suspend fun exists(videoId: String): Boolean

    @Query("UPDATE downloads SET sponsorBlockSegmentsJson = :json WHERE videoId = :videoId")
    suspend fun updateSponsorBlockData(
        videoId: String,
        json: String,
    )

    @Query("SELECT sponsorBlockSegmentsJson FROM downloads WHERE videoId = :videoId")
    suspend fun getSponsorBlockData(videoId: String): String?

    // ===== Download Items (children) =====

    // ABORT: two downloads resolving to one path must fail loudly, not delete each other's rows.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItem(item: DownloadItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItems(items: List<DownloadItemEntity>)

    @Update
    suspend fun updateItem(item: DownloadItemEntity)

    @Query("UPDATE download_items SET downloadedBytes = :downloadedBytes, status = :status WHERE id = :itemId")
    suspend fun updateProgress(
        itemId: Int,
        downloadedBytes: Long,
        status: DownloadItemStatus,
    )

    @Query("UPDATE download_items SET status = :status WHERE id = :itemId")
    suspend fun updateStatus(
        itemId: Int,
        status: DownloadItemStatus,
    )

    @Query("UPDATE download_items SET status = :status WHERE videoId = :videoId")
    suspend fun updateAllItemsStatus(
        videoId: String,
        status: DownloadItemStatus,
    )

    /** A running download's progress, leaving its status alone so a pause written meanwhile stands. */
    @Query("UPDATE download_items SET downloadedBytes = :downloadedBytes, totalBytes = :totalBytes WHERE id = :itemId")
    suspend fun updateBytes(
        itemId: Int,
        downloadedBytes: Long,
        totalBytes: Long,
    )

    @Query("UPDATE download_items SET downloadedBytes = :downloadedBytes, totalBytes = :totalBytes, status = :status WHERE id = :itemId")
    suspend fun updateItemFull(
        itemId: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        status: DownloadItemStatus,
    )

    @Query("UPDATE download_items SET filePath = :filePath, fileName = :fileName WHERE id = :itemId")
    suspend fun updateItemLocation(
        itemId: Int,
        filePath: String,
        fileName: String,
    )

    @Query("SELECT * FROM download_items WHERE id = :itemId")
    suspend fun getItemById(itemId: Int): DownloadItemEntity?

    @Query("SELECT * FROM download_items WHERE videoId = :videoId")
    suspend fun getItemsByVideoId(videoId: String): List<DownloadItemEntity>

    @Query("DELETE FROM download_items WHERE id = :itemId")
    suspend fun deleteItem(itemId: Int)

    // ===== Combined Queries =====

    @Transaction
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAllDownloadsWithItems(): Flow<List<DownloadWithItems>>

    @Transaction
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    suspend fun getAllDownloadsWithItemsOnce(): List<DownloadWithItems>

    @Transaction
    @Query("SELECT * FROM downloads WHERE videoId = :videoId")
    suspend fun getDownloadWithItems(videoId: String): DownloadWithItems?

    @Transaction
    @Query("SELECT * FROM downloads WHERE videoId = :videoId")
    fun getDownloadWithItemsFlow(videoId: String): Flow<DownloadWithItems?>

    /** Get downloads that have at least one item with VIDEO fileType */
    @Transaction
    @Query(
        """
        SELECT DISTINCT d.* FROM downloads d 
        INNER JOIN download_items di ON d.videoId = di.videoId 
        WHERE di.fileType = 'VIDEO' 
        ORDER BY d.createdAt DESC
    """,
    )
    fun getVideoDownloads(): Flow<List<DownloadWithItems>>

    /** Get downloads that have AUDIO items but NO VIDEO items */
    @Transaction
    @Query(
        """
        SELECT DISTINCT d.* FROM downloads d 
        INNER JOIN download_items di ON d.videoId = di.videoId 
        WHERE di.fileType = 'AUDIO' 
        AND d.videoId NOT IN (
            SELECT videoId FROM download_items WHERE fileType = 'VIDEO'
        )
        ORDER BY d.createdAt DESC
    """,
    )
    fun getAudioOnlyDownloads(): Flow<List<DownloadWithItems>>

    /** Get downloads with active (DOWNLOADING/PENDING) items */
    @Transaction
    @Query(
        """
        SELECT DISTINCT d.* FROM downloads d 
        INNER JOIN download_items di ON d.videoId = di.videoId 
        WHERE di.status IN ('DOWNLOADING', 'PENDING', 'PAUSED')
        ORDER BY d.createdAt DESC
    """,
    )
    fun getActiveDownloads(): Flow<List<DownloadWithItems>>

    /**
     * Where the finished audio-only download of [videoId] was saved. Blocking, for the player's
     * loader thread, which has to answer before it opens the stream.
     */
    @Query(
        """
        SELECT filePath FROM download_items
        WHERE videoId = :videoId AND fileType = 'AUDIO' AND status = 'COMPLETED'
        AND videoId NOT IN (SELECT videoId FROM download_items WHERE fileType = 'VIDEO')
        LIMIT 1
    """,
    )
    fun completedAudioPathBlocking(videoId: String): String?

    /** The gain saved with [videoId]'s finished song download. */
    @Query(
        """
        SELECT loudnessGainDb FROM download_items
        WHERE videoId = :videoId AND fileType = 'AUDIO' AND status = 'COMPLETED'
        LIMIT 1
    """,
    )
    suspend fun completedAudioLoudnessGain(videoId: String): Float?

    /** Check if a completed download exists for a video */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM download_items 
            WHERE videoId = :videoId AND status = 'COMPLETED'
        )
    """,
    )
    suspend fun isDownloaded(videoId: String): Boolean

    /** A finished download's file row: where it ended up, what it is, and that it is complete. */
    @Query(
        """
        UPDATE download_items SET filePath = :filePath, fileName = :fileName, format = :format, mimeType = :mimeType,
            quality = :quality, downloadedBytes = :size, totalBytes = :size, status = 'COMPLETED',
            loudnessGainDb = :loudnessGainDb
        WHERE id = :itemId
        """,
    )
    suspend fun completeItem(
        itemId: Int,
        filePath: String,
        fileName: String,
        format: String,
        mimeType: String,
        quality: String,
        size: Long,
        loudnessGainDb: Float?,
    )

    @Query("UPDATE downloads SET thumbnailPath = :path WHERE videoId = :videoId")
    suspend fun updateThumbnailPath(
        videoId: String,
        path: String,
    )

    /** What the watch page added to a download's metadata, so a retry does not ask again. */
    @Query(
        """
        UPDATE downloads SET title = :title, uploader = :uploader, channelId = :channelId, description = :description,
            releaseDate = :releaseDate, viewCount = :viewCount, likeCount = :likeCount, requestJson = :requestJson
        WHERE videoId = :videoId
        """,
    )
    suspend fun updateMetadata(
        videoId: String,
        title: String,
        uploader: String,
        channelId: String,
        description: String,
        releaseDate: String?,
        viewCount: Long,
        likeCount: Long,
        requestJson: String,
    )

    /** A download's stored request, rewritten when a step adds to it so a retry does not repeat the step. */
    @Query("UPDATE downloads SET requestJson = :requestJson WHERE videoId = :videoId")
    suspend fun updateRequest(
        videoId: String,
        requestJson: String,
    )

    /** A song's album and artists, moved in from where older versions kept them. */
    @Query(
        """
        UPDATE downloads SET kind = :kind, album = COALESCE(:album, album), albumId = COALESCE(:albumId, albumId),
            artistsJson = COALESCE(:artistsJson, artistsJson), channelId = :channelId, uploader = :uploader
        WHERE videoId = :videoId
        """,
    )
    suspend fun updateMusicMetadata(
        videoId: String,
        kind: io.github.aedev.flow.data.video.downloader.tags.DownloadKind,
        album: String?,
        albumId: String?,
        artistsJson: String?,
        channelId: String,
        uploader: String,
    )

    /** Whether anything is waiting for, or in the middle of, a transfer. */
    @Query("SELECT EXISTS(SELECT 1 FROM download_items WHERE status IN ('PENDING', 'DOWNLOADING'))")
    suspend fun hasQueuedDownloads(): Boolean

    /**
     * Every download as the queue work sees it, oldest first. Finished and failed rows are included
     * on purpose: a row that left the waiting states still has a running job that must be let finish,
     * and only a row that is paused, cancelled or gone asks for its job to stop.
     */
    @Query(
        """
        SELECT d.videoId AS videoId, MIN(d.createdAt) AS createdAt, di.status AS status FROM downloads d
        INNER JOIN download_items di ON d.videoId = di.videoId
        GROUP BY d.videoId
        ORDER BY createdAt
        """,
    )
    fun observeQueue(): Flow<List<QueuedDownload>>

    /** A download the app was killed in the middle of goes back to waiting, so the queue picks it up. */
    @Query("UPDATE download_items SET status = 'PENDING' WHERE status = 'DOWNLOADING'")
    suspend fun requeueInterrupted()

    /** Get total download storage size */
    @Query("SELECT COALESCE(SUM(totalBytes), 0) FROM download_items WHERE status = 'COMPLETED'")
    suspend fun getTotalDownloadSize(): Long

    /** Count completed downloads */
    @Query("SELECT COUNT(DISTINCT videoId) FROM download_items WHERE status = 'COMPLETED'")
    suspend fun getCompletedDownloadCount(): Int

    /** Check if a download item already exists for a given file path */
    @Query("SELECT EXISTS(SELECT 1 FROM download_items WHERE filePath = :filePath)")
    suspend fun existsByFilePath(filePath: String): Boolean
}
