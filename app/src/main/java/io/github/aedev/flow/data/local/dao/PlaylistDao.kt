package io.github.aedev.flow.data.local.dao

import androidx.room.*
import io.github.aedev.flow.data.local.entity.PlaylistEntity
import io.github.aedev.flow.data.local.entity.PlaylistVideoCrossRef
import io.github.aedev.flow.data.local.entity.VideoEntity
import kotlinx.coroutines.flow.Flow

data class PlaylistWithCount(
    @Embedded val playlist: PlaylistEntity,
    @ColumnInfo(name = "video_count") val videoCount: Int,
)

/** A playlist's video joined with per-playlist metadata (when it was added to that playlist). */
data class PlaylistVideoWithMeta(
    @Embedded val video: VideoEntity,
    @ColumnInfo(name = "addedAt") val addedAt: Long,
)

/** Where one video sits in a playlist, read to work out which rows a change has to write. */
data class PlaylistVideoPosition(
    val videoId: String,
    val position: Long,
)

@Dao
interface PlaylistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistRow(playlist: PlaylistEntity)

    /** Re-saving or syncing a playlist keeps the place the viewer dragged it to. */
    @Transaction
    suspend fun insertPlaylist(playlist: PlaylistEntity) {
        val keptPosition = if (playlist.position != 0) playlist.position else getPlaylist(playlist.id)?.position ?: 0
        insertPlaylistRow(playlist.copy(position = keptPosition))
    }

    @Query("UPDATE playlists SET position = :position WHERE id = :id")
    suspend fun updatePlaylistPosition(
        id: String,
        position: Int,
    )

    /** Stores [orderedIds] as the custom order, starting at 1 so playlists added later lead the list. */
    @Transaction
    suspend fun reorderPlaylists(orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, id -> updatePlaylistPosition(id, index + 1) }
    }

    @Query(
        "SELECT playlists.*, COUNT(playlist_video_cross_ref.videoId) as video_count FROM playlists LEFT JOIN playlist_video_cross_ref ON playlists.id = playlist_video_cross_ref.playlistId GROUP BY playlists.id ORDER BY createdAt DESC",
    )
    fun getAllPlaylistsWithCount(): Flow<List<PlaylistWithCount>>

    @Query(
        "SELECT playlists.*, COUNT(playlist_video_cross_ref.videoId) as video_count FROM playlists LEFT JOIN playlist_video_cross_ref ON playlists.id = playlist_video_cross_ref.playlistId WHERE isMusic = 1 GROUP BY playlists.id ORDER BY createdAt DESC",
    )
    fun getMusicPlaylistsWithCount(): Flow<List<PlaylistWithCount>>

    @Query(
        "SELECT playlists.*, COUNT(playlist_video_cross_ref.videoId) as video_count FROM playlists LEFT JOIN playlist_video_cross_ref ON playlists.id = playlist_video_cross_ref.playlistId WHERE isMusic = 0 AND playlists.id NOT IN ('watch_later', 'saved_shorts') GROUP BY playlists.id ORDER BY createdAt DESC",
    )
    fun getVideoPlaylistsWithCount(): Flow<List<PlaylistWithCount>>

    @Query(
        "SELECT playlists.*, COUNT(playlist_video_cross_ref.videoId) as video_count FROM playlists LEFT JOIN playlist_video_cross_ref ON playlists.id = playlist_video_cross_ref.playlistId WHERE isMusic = 0 AND isUserCreated = 1 AND playlists.id NOT IN ('watch_later', 'saved_shorts') GROUP BY playlists.id ORDER BY createdAt DESC",
    )
    fun getUserCreatedVideoPlaylistsWithCount(): Flow<List<PlaylistWithCount>>

    @Query(
        "SELECT playlists.*, COUNT(playlist_video_cross_ref.videoId) as video_count FROM playlists LEFT JOIN playlist_video_cross_ref ON playlists.id = playlist_video_cross_ref.playlistId WHERE isMusic = 0 AND isUserCreated = 0 AND playlists.id NOT IN ('watch_later', 'saved_shorts') GROUP BY playlists.id ORDER BY createdAt DESC",
    )
    fun getSavedVideoPlaylistsWithCount(): Flow<List<PlaylistWithCount>>

    @Query(
        "SELECT playlists.*, COUNT(playlist_video_cross_ref.videoId) as video_count FROM playlists LEFT JOIN playlist_video_cross_ref ON playlists.id = playlist_video_cross_ref.playlistId WHERE isMusic = 1 AND isUserCreated = 1 GROUP BY playlists.id ORDER BY createdAt DESC",
    )
    fun getUserCreatedMusicPlaylistsWithCount(): Flow<List<PlaylistWithCount>>

    @Query(
        "SELECT playlists.*, COUNT(playlist_video_cross_ref.videoId) as video_count FROM playlists LEFT JOIN playlist_video_cross_ref ON playlists.id = playlist_video_cross_ref.playlistId WHERE isMusic = 1 AND isUserCreated = 0 GROUP BY playlists.id ORDER BY createdAt DESC",
    )
    fun getSavedMusicPlaylistsWithCount(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE isMusic = 1 ORDER BY createdAt DESC")
    fun getMusicPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE isMusic = 0 ORDER BY createdAt DESC")
    fun getVideoPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylist(id: String): PlaylistEntity?

    @Query("UPDATE playlists SET isMusic = :isMusic WHERE id = :id")
    suspend fun setPlaylistIsMusic(
        id: String,
        isMusic: Boolean,
    )

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observePlaylist(id: String): Flow<PlaylistEntity?>

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun updatePlaylistName(
        id: String,
        name: String,
    )

    @Query("UPDATE playlists SET name = :name, description = :description, isPrivate = :isPrivate WHERE id = :id")
    suspend fun updatePlaylistMetadata(
        id: String,
        name: String,
        description: String,
        isPrivate: Boolean,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistVideoCrossRef(crossRef: PlaylistVideoCrossRef)

    @Query("SELECT * FROM playlist_video_cross_ref WHERE playlistId = :playlistId AND videoId IN (:videoIds)")
    suspend fun getCrossRefs(
        playlistId: String,
        videoIds: List<String>,
    ): List<PlaylistVideoCrossRef>

    /** Removes [videoIds] as one change and returns their entries, so the removal can be undone. */
    @Transaction
    suspend fun takePlaylistVideos(
        playlistId: String,
        videoIds: List<String>,
    ): List<PlaylistVideoCrossRef> {
        val taken = getCrossRefs(playlistId, videoIds)
        taken.forEach { removeVideoFromPlaylist(playlistId, it.videoId) }
        updatePlaylistThumbnail(playlistId, getFirstVideoThumbnail(playlistId).orEmpty())
        return taken
    }

    /** Adds a whole playlist with its entries as one change, so it never appears half filled. */
    @Transaction
    suspend fun insertPlaylistWithVideos(
        playlist: PlaylistEntity,
        entries: List<PlaylistVideoCrossRef>,
    ) {
        insertPlaylist(playlist)
        entries.forEach { insertPlaylistVideoCrossRef(it) }
    }

    /**
     * Appends [videoIds] after the playlist's last position as one change, so observers re-query once
     * and never see a half-added list. A video already in the playlist keeps its place.
     */
    @Transaction
    suspend fun appendPlaylistVideos(
        playlistId: String,
        videoIds: List<String>,
        fallbackThumbnail: String,
    ) {
        val alreadyInPlaylist = getVideoIdsInPlaylist(playlistId).toHashSet()
        var nextPosition = (getMaxPlaylistPosition(playlistId) ?: -1L) + 1L
        videoIds.forEach { videoId ->
            if (alreadyInPlaylist.add(videoId)) {
                insertPlaylistVideoCrossRef(PlaylistVideoCrossRef(playlistId = playlistId, videoId = videoId, position = nextPosition++))
            }
        }
        updatePlaylistThumbnail(playlistId, getFirstVideoThumbnail(playlistId) ?: fallbackThumbnail)
    }

    /** Puts entries back where they were, with their original position and date added. */
    @Transaction
    suspend fun restorePlaylistVideos(entries: List<PlaylistVideoCrossRef>) {
        entries.forEach { insertPlaylistVideoCrossRef(it) }
        entries.map { it.playlistId }.distinct().forEach { playlistId ->
            updatePlaylistThumbnail(playlistId, getFirstVideoThumbnail(playlistId).orEmpty())
        }
    }

    /** Applies a whole new order at once, so no intermediate order with duplicate positions is emitted. */
    @Transaction
    suspend fun reorderPlaylistVideos(
        playlistId: String,
        orderedVideoIds: List<String>,
    ) {
        orderedVideoIds.forEachIndexed { index, videoId -> updatePlaylistVideoPosition(playlistId, videoId, index.toLong()) }
        updatePlaylistThumbnail(playlistId, getFirstVideoThumbnail(playlistId).orEmpty())
    }

    /**
     * Makes the playlist hold exactly [orderedVideoIds], in that order, as one change. Videos it
     * already holds keep their date added, and an unchanged playlist is not written at all, so its
     * observers do not re-query. A playlist removed while its refresh was loading is left gone (#2232).
     */
    @Transaction
    suspend fun replacePlaylistVideos(
        playlistId: String,
        orderedVideoIds: List<String>,
    ) {
        val playlist = getPlaylist(playlistId) ?: return
        val plan = planPlaylistSync(getPlaylistVideoPositions(playlistId), orderedVideoIds)
        plan.remove.forEach { removeVideoFromPlaylist(playlistId, it) }
        plan.move.forEach { updatePlaylistVideoPosition(playlistId, it.videoId, it.position) }
        plan.insert.forEach {
            insertPlaylistVideoCrossRef(PlaylistVideoCrossRef(playlistId = playlistId, videoId = it.videoId, position = it.position))
        }
        val thumbnail = getFirstVideoThumbnail(playlistId).orEmpty()
        if (thumbnail != playlist.thumbnailUrl) updatePlaylistThumbnail(playlistId, thumbnail)
    }

    @Query("SELECT videoId, position FROM playlist_video_cross_ref WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getPlaylistVideoPositions(playlistId: String): List<PlaylistVideoPosition>

    @Query("UPDATE playlist_video_cross_ref SET position = :position WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun updatePlaylistVideoPosition(
        playlistId: String,
        videoId: String,
        position: Long,
    )

    @Query("DELETE FROM playlist_video_cross_ref WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun removeVideoFromPlaylist(
        playlistId: String,
        videoId: String,
    )

    @Transaction
    @Query(
        "SELECT videos.* FROM videos INNER JOIN playlist_video_cross_ref ON videos.id = playlist_video_cross_ref.videoId WHERE playlist_video_cross_ref.playlistId = :playlistId ORDER BY playlist_video_cross_ref.position ASC",
    )
    fun getVideosForPlaylist(playlistId: String): Flow<List<VideoEntity>>

    @Query("SELECT playlistId FROM playlist_video_cross_ref WHERE videoId = :videoId")
    suspend fun getPlaylistIdsForVideo(videoId: String): List<String>

    @Transaction
    @Query(
        "SELECT videos.*, playlist_video_cross_ref.addedAt AS addedAt FROM videos INNER JOIN playlist_video_cross_ref ON videos.id = playlist_video_cross_ref.videoId WHERE playlist_video_cross_ref.playlistId = :playlistId ORDER BY playlist_video_cross_ref.position ASC",
    )
    fun getVideosWithMetaForPlaylist(playlistId: String): Flow<List<PlaylistVideoWithMeta>>

    @Query("SELECT COUNT(*) FROM playlist_video_cross_ref WHERE playlistId = :playlistId")
    fun getPlaylistVideoCount(playlistId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM playlist_video_cross_ref WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun isVideoInPlaylist(
        playlistId: String,
        videoId: String,
    ): Int

    @Query("SELECT videoId FROM playlist_video_cross_ref WHERE playlistId = :playlistId")
    suspend fun getVideoIdsInPlaylist(playlistId: String): List<String>

    /** Null when the playlist is empty. */
    @Query("SELECT MAX(position) FROM playlist_video_cross_ref WHERE playlistId = :playlistId")
    suspend fun getMaxPlaylistPosition(playlistId: String): Long?

    @Query(
        """
        SELECT COUNT(*) FROM playlist_video_cross_ref r
        INNER JOIN playlists p ON p.id = r.playlistId
        WHERE r.videoId = :videoId AND p.isMusic = 0
    """,
    )
    fun getVideoPlaylistMembershipCount(videoId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM playlists WHERE id = :id AND isUserCreated = 0")
    suspend fun isSavedExternalPlaylist(id: String): Int

    @Query("SELECT COUNT(*) FROM playlists WHERE id = :id")
    suspend fun isPlaylistInDb(id: String): Int

    @Query("SELECT * FROM playlist_video_cross_ref")
    suspend fun getAllPlaylistVideoCrossRefs(): List<PlaylistVideoCrossRef>

    /** Distinct videos saved across Watch Later and all non-music video playlists (excludes saved shorts). */
    @Query(
        """
        SELECT v.* FROM videos v
        INNER JOIN playlist_video_cross_ref r ON v.id = r.videoId
        INNER JOIN playlists p ON p.id = r.playlistId
        WHERE p.isMusic = 0 AND p.id != 'saved_shorts'
        GROUP BY v.id
        ORDER BY MIN(r.position) ASC
    """,
    )
    suspend fun getSavedVideoPlaylistVideos(): List<VideoEntity>

    @Query("UPDATE playlists SET thumbnailUrl = :thumbnailUrl WHERE id = :id")
    suspend fun updatePlaylistThumbnail(
        id: String,
        thumbnailUrl: String,
    )

    @Query(
        "SELECT v.thumbnailUrl FROM videos v INNER JOIN playlist_video_cross_ref r ON v.id = r.videoId WHERE r.playlistId = :playlistId ORDER BY r.position ASC LIMIT 1",
    )
    suspend fun getFirstVideoThumbnail(playlistId: String): String?

    /**
     * Returns stub VideoEntities inside music playlists that are missing a title OR a thumbnail.
     * Used for background enrichment — e.g. synced album tracks arrive with a title but no artwork.
     */
    @Query(
        """
        SELECT DISTINCT v.* FROM videos v
        INNER JOIN playlist_video_cross_ref r ON v.id = r.videoId
        INNER JOIN playlists p ON p.id = r.playlistId
        WHERE p.isMusic = 1 AND (v.title = '' OR v.title IS NULL OR v.thumbnailUrl = '' OR v.thumbnailUrl IS NULL)
        LIMIT 200
    """,
    )
    suspend fun getMusicPlaylistStubVideos(): List<VideoEntity>

    /** Music playlist/album ids whose cover is blank — recompute from the first track after enrichment. */
    @Query("SELECT id FROM playlists WHERE isMusic = 1 AND (thumbnailUrl = '' OR thumbnailUrl IS NULL)")
    suspend fun getMusicPlaylistsMissingThumbnail(): List<String>
}
