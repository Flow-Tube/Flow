package io.github.aedev.flow.data.local

import io.github.aedev.flow.data.local.dao.PlaylistDao
import io.github.aedev.flow.data.local.dao.PlaylistWithCount
import io.github.aedev.flow.data.local.dao.VideoDao
import io.github.aedev.flow.data.local.entity.PlaylistEntity
import io.github.aedev.flow.data.local.entity.PlaylistVideoCrossRef
import io.github.aedev.flow.data.local.entity.VideoEntity
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.utils.relativedate.RelativeUploadDateParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepository
    @Inject
    constructor(
        private val playlistDao: PlaylistDao,
        private val videoDao: VideoDao,
    ) {
        constructor(context: android.content.Context) : this(
            AppDatabase.profile(context).playlistDao(),
            AppDatabase.profile(context).videoDao(),
        )

        // Watch Later Logic (using a special hardcoded playlist ID "watch_later")
        companion object {
            const val WATCH_LATER_ID = "watch_later"
            const val SAVED_SHORTS_ID = "saved_shorts"

            // Built-in pages read from the likes store, not playlist rows.
            const val LIKED_VIDEOS_ID = "liked_videos"
            const val LIKED_MUSIC_ID = "liked_music"
        }

        suspend fun updateVideoMetadata(video: Video) {
            videoDao.mergeMetadata(listOf(normalizedEntity(video)))
        }

        private fun normalizedEntity(video: Video): VideoEntity {
            val normalizedVideo =
                RelativeUploadDateParser
                    .parse(video.uploadDate, YouTube.locale.hl)
                    ?.let { parsedTimestamp ->
                        val stableTimestamp =
                            video.timestamp
                                .takeIf { it > 0L }
                                ?.let { minOf(it, parsedTimestamp) }
                                ?: parsedTimestamp
                        video.copy(timestamp = stableTimestamp)
                    }
                    ?: video
            return VideoEntity.fromDomain(normalizedVideo)
        }

        // Saved Shorts Logic
        suspend fun addToSavedShorts(video: Video) {
            ensureFixedPlaylist(SAVED_SHORTS_ID)

            // Save video
            updateVideoMetadata(video)

            // Add relationship
            val position = System.currentTimeMillis()
            playlistDao.insertPlaylistVideoCrossRef(
                PlaylistVideoCrossRef(
                    playlistId = SAVED_SHORTS_ID,
                    videoId = video.id,
                    position = -position,
                ),
            )
        }

        suspend fun removeFromSavedShorts(videoId: String) {
            playlistDao.removeVideoFromPlaylist(SAVED_SHORTS_ID, videoId)
        }

        fun getSavedShortsFlow(): Flow<List<Video>> =
            playlistDao.getVideosForPlaylist(SAVED_SHORTS_ID).map { entities ->
                entities.map { it.toDomain() }
            }

        fun getVideoOnlySavedShortsFlow(): Flow<List<Video>> = getSavedShortsFlow().map { list -> list.filter { !it.isMusic } }

        suspend fun isInSavedShorts(videoId: String): Boolean {
            val videos = playlistDao.getVideosForPlaylist(SAVED_SHORTS_ID).firstOrNull() ?: emptyList()
            return videos.any { it.id == videoId }
        }

        suspend fun addToWatchLater(video: Video) {
            try {
                android.util.Log.d("PlaylistRepository", "Adding video to Watch Later: ${video.id}")
                ensureFixedPlaylist(WATCH_LATER_ID)

                // Save video
                android.util.Log.d("PlaylistRepository", "Inserting video metadata")
                updateVideoMetadata(video)

                // Add relationship
                val position = System.currentTimeMillis()
                android.util.Log.d("PlaylistRepository", "Inserting cross-ref")
                playlistDao.insertPlaylistVideoCrossRef(
                    PlaylistVideoCrossRef(
                        playlistId = WATCH_LATER_ID,
                        videoId = video.id,
                        position = -position,
                    ),
                )
                android.util.Log.d("PlaylistRepository", "Successfully added to Watch Later")
            } catch (e: Exception) {
                android.util.Log.e("PlaylistRepository", "Failed to add to Watch Later", e)
                throw e
            }
        }

        /**
         * Adds [videos] below what Watch later or Saved Shorts already holds, in the given order,
         * skipping ones already there. Returns how many were added.
         */
        suspend fun importIntoFixedList(
            playlistId: String,
            videos: List<Video>,
        ): Int {
            ensureFixedPlaylist(playlistId)
            val present = playlistDao.getVideoIdsInPlaylist(playlistId).toHashSet()
            val added = videos.filter { present.add(it.id) }
            if (added.isEmpty()) return 0
            videoDao.insertVideosOrIgnore(added.map(::normalizedEntity))
            val now = System.currentTimeMillis()
            var nextPosition = (playlistDao.getMaxPlaylistPosition(playlistId) ?: -1L) + 1L
            playlistDao.restorePlaylistVideos(
                added.map { video ->
                    PlaylistVideoCrossRef(playlistId, video.id, nextPosition++, video.addedAtInPlaylist ?: now)
                },
            )
            return added.size
        }

        private suspend fun ensureFixedPlaylist(playlistId: String) {
            if (playlistDao.getPlaylist(playlistId) != null) return
            val watchLater = playlistId == WATCH_LATER_ID
            playlistDao.insertPlaylist(
                PlaylistEntity(
                    id = playlistId,
                    name = if (watchLater) "Watch Later" else "Saved Shorts",
                    description = if (watchLater) "Your watch later list" else "Your saved shorts",
                    thumbnailUrl = "",
                    isPrivate = true,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }

        suspend fun removeFromWatchLater(videoId: String) {
            playlistDao.removeVideoFromPlaylist(WATCH_LATER_ID, videoId)
        }

        suspend fun clearWatchLater() {
            playlistDao.deletePlaylist(WATCH_LATER_ID)
        }

        fun getWatchLaterVideosFlow(): Flow<List<Video>> =
            playlistDao.getVideosForPlaylist(WATCH_LATER_ID).map { entities ->
                entities.map { it.toDomain() }
            }

        fun getVideoOnlyWatchLaterFlow(): Flow<List<Video>> = getWatchLaterVideosFlow().map { list -> list.filter { !it.isMusic } }

        fun getMusicOnlyWatchLaterFlow(): Flow<List<Video>> = getWatchLaterVideosFlow().map { list -> list.filter { it.isMusic } }

        fun getWatchLaterIdsFlow(): Flow<Set<String>> =
            playlistDao.getVideosForPlaylist(WATCH_LATER_ID).map { entities ->
                entities.map { it.id }.toSet()
            }

        fun isVideoSavedToAnyPlaylistFlow(videoId: String): Flow<Boolean> =
            playlistDao.getVideoPlaylistMembershipCount(videoId).map {
                it >
                    0
            }

        suspend fun isInWatchLater(videoId: String): Boolean =
            try {
                playlistDao.isVideoInPlaylist(WATCH_LATER_ID, videoId) > 0
            } catch (e: Exception) {
                android.util.Log.e("PlaylistRepository", "Error checking watch later status", e)
                false
            }

        suspend fun isVideoInPlaylist(
            playlistId: String,
            videoId: String,
        ): Boolean =
            try {
                playlistDao.isVideoInPlaylist(playlistId, videoId) > 0
            } catch (e: Exception) {
                android.util.Log.e("PlaylistRepository", "Error checking playlist status", e)
                false
            }

        // Playlist Management
        suspend fun createPlaylist(
            playlistId: String,
            name: String,
            description: String,
            isPrivate: Boolean,
            isMusic: Boolean = false,
        ) {
            val entity =
                PlaylistEntity(
                    id = playlistId,
                    name = name,
                    description = description,
                    thumbnailUrl = "",
                    isPrivate = isPrivate,
                    createdAt = System.currentTimeMillis(),
                    isMusic = isMusic,
                    isUserCreated = true,
                )
            playlistDao.insertPlaylist(entity)
        }

        /**
         * Adds an imported playlist as the viewer's own, in the given order. Videos already in the
         * library keep their stored metadata; the file only fills in ones Flow has never seen. Several
         * imported at once each take a different [createdAt], which is also the playlist's id.
         */
        suspend fun importPlaylist(
            name: String,
            description: String,
            videos: List<Video>,
            isMusic: Boolean = false,
            createdAt: Long = System.currentTimeMillis(),
        ): String {
            val playlistId = createdAt.toString()
            videoDao.insertVideosOrIgnore(videos.map(::normalizedEntity))
            playlistDao.insertPlaylistWithVideos(
                playlist =
                    PlaylistEntity(
                        id = playlistId,
                        name = name,
                        description = description,
                        thumbnailUrl = videos.firstOrNull()?.thumbnailUrl.orEmpty(),
                        isPrivate = true,
                        createdAt = createdAt,
                        isMusic = isMusic,
                        isUserCreated = true,
                    ),
                entries =
                    videos.mapIndexed { index, video ->
                        PlaylistVideoCrossRef(
                            playlistId = playlistId,
                            videoId = video.id,
                            position = index.toLong(),
                            addedAt = video.addedAtInPlaylist ?: createdAt,
                        )
                    },
            )
            return playlistId
        }

        suspend fun saveExternalVideoPlaylist(
            id: String,
            name: String,
            description: String,
            thumbnailUrl: String,
        ) {
            val entity =
                PlaylistEntity(
                    id = id,
                    name = name,
                    description = description,
                    thumbnailUrl = thumbnailUrl,
                    isPrivate = false,
                    createdAt = System.currentTimeMillis(),
                    isMusic = false,
                    isUserCreated = false,
                )
            playlistDao.insertPlaylist(entity)
        }

        suspend fun saveExternalMusicPlaylist(
            id: String,
            name: String,
            description: String,
            thumbnailUrl: String,
        ) {
            val entity =
                PlaylistEntity(
                    id = id,
                    name = name,
                    description = description,
                    thumbnailUrl = thumbnailUrl,
                    isPrivate = false,
                    createdAt = System.currentTimeMillis(),
                    isMusic = true,
                    isUserCreated = false,
                )
            playlistDao.insertPlaylist(entity)
        }

        /** Moves a stored playlist to the music library, or back to the video library. */
        suspend fun moveToLibrary(
            playlistId: String,
            isMusic: Boolean,
        ) = playlistDao.setPlaylistIsMusic(playlistId, isMusic)

        suspend fun unsaveExternalPlaylist(playlistId: String) {
            val entity = playlistDao.getPlaylist(playlistId)
            if (entity != null && !entity.isUserCreated) {
                playlistDao.deletePlaylist(playlistId)
            }
        }

        suspend fun isExternalPlaylistSaved(playlistId: String): Boolean = playlistDao.isSavedExternalPlaylist(playlistId) > 0

        suspend fun updatePlaylistName(
            playlistId: String,
            name: String,
        ) {
            playlistDao.updatePlaylistName(playlistId, name)
        }

        suspend fun updatePlaylistMetadata(
            playlistId: String,
            name: String,
            description: String,
            isPrivate: Boolean,
        ) {
            playlistDao.updatePlaylistMetadata(playlistId, name, description, isPrivate)
        }

        suspend fun deletePlaylist(playlistId: String) {
            playlistDao.deletePlaylist(playlistId)
        }

        suspend fun addVideoToPlaylist(
            playlistId: String,
            video: Video,
        ) {
            addVideosToPlaylist(playlistId, listOf(video))
        }

        /**
         * Appends [videos] to the end of the playlist, preserving the order they are given in.
         * Positions are a plain ascending ordering key here, unlike the negative "newest first"
         * timestamps Watch Later and Saved Shorts stamp on their own rows.
         */
        suspend fun addVideosToPlaylist(
            targetPlaylistId: String,
            videos: List<Video>,
        ) {
            if (videos.isEmpty()) return
            try {
                videoDao.mergeMetadata(videos.map(::normalizedEntity))
                playlistDao.appendPlaylistVideos(targetPlaylistId, videos.map { it.id }, videos.first().thumbnailUrl)
            } catch (e: Exception) {
                android.util.Log.e("PlaylistRepository", "Failed to add to playlist $targetPlaylistId", e)
                throw e
            }
        }

        suspend fun removeVideoFromPlaylist(
            playlistId: String,
            videoId: String,
        ) {
            playlistDao.removeVideoFromPlaylist(playlistId, videoId)
            val newThumb = playlistDao.getFirstVideoThumbnail(playlistId) ?: ""
            playlistDao.updatePlaylistThumbnail(playlistId, newThumb)
        }

        /** Removes [videoIds] and returns what [restorePlaylistVideos] needs to put them back. */
        suspend fun takeVideosFromPlaylist(
            playlistId: String,
            videoIds: Collection<String>,
        ): List<PlaylistVideoCrossRef> = playlistDao.takePlaylistVideos(playlistId, videoIds.toList())

        suspend fun restorePlaylistVideos(entries: List<PlaylistVideoCrossRef>) {
            if (entries.isNotEmpty()) playlistDao.restorePlaylistVideos(entries)
        }

        suspend fun reorderVideosInPlaylist(
            playlistId: String,
            orderedVideoIds: List<String>,
        ) {
            playlistDao.reorderPlaylistVideos(playlistId, orderedVideoIds)
        }

        suspend fun reorderPlaylists(orderedIds: List<String>) = playlistDao.reorderPlaylists(orderedIds)

        fun getAllPlaylistsFlow(): Flow<List<PlaylistInfo>> =
            playlistDao.getVideoPlaylistsWithCount().map { items -> items.map(PlaylistWithCount::toPlaylistInfo) }

        fun getUserCreatedVideoPlaylistsFlow(): Flow<List<PlaylistInfo>> =
            playlistDao.getUserCreatedVideoPlaylistsWithCount().map { items -> items.map(PlaylistWithCount::toPlaylistInfo) }

        fun getSavedVideoPlaylistsFlow(): Flow<List<PlaylistInfo>> =
            playlistDao.getSavedVideoPlaylistsWithCount().map { items -> items.map(PlaylistWithCount::toPlaylistInfo) }

        fun getMusicPlaylistsFlow(): Flow<List<PlaylistInfo>> =
            playlistDao.getMusicPlaylistsWithCount().map { items -> items.map(PlaylistWithCount::toPlaylistInfo) }

        fun getUserCreatedMusicPlaylistsFlow(): Flow<List<PlaylistInfo>> =
            playlistDao.getUserCreatedMusicPlaylistsWithCount().map { items -> items.map(PlaylistWithCount::toPlaylistInfo) }

        fun getSavedMusicPlaylistsFlow(): Flow<List<PlaylistInfo>> =
            playlistDao.getSavedMusicPlaylistsWithCount().map { items -> items.map(PlaylistWithCount::toPlaylistInfo) }

        suspend fun getSavedVideoPlaylistVideos(): List<Video> = playlistDao.getSavedVideoPlaylistVideos().map { it.toDomain() }

        suspend fun getPlaylistIdsForVideo(videoId: String): List<String> = playlistDao.getPlaylistIdsForVideo(videoId)

        fun getPlaylistVideosFlow(playlistId: String): Flow<List<Video>> =
            playlistDao.getVideosForPlaylist(playlistId).map { entities ->
                entities.map { it.toDomain() }
            }

        /** Like [getPlaylistVideosFlow] but each video carries when it was added to this playlist. */
        fun getPlaylistVideosWithAddedAtFlow(playlistId: String): Flow<List<Video>> =
            playlistDao.getVideosWithMetaForPlaylist(playlistId).map { rows ->
                // addedAt <= 0 means "unknown" (legacy rows reordered before the addedAt column
                // existed) — surface null so the UI falls back instead of showing an epoch date.
                rows.map { it.video.toDomain().copy(addedAtInPlaylist = it.addedAt.takeIf { ts -> ts > 0L }) }
            }

        /**
         * Reconciles a saved (not-owned) playlist's local copy with a fresh remote fetch: upserts each
         * remote video's metadata, restores creator order, adds newly-published videos and drops ones
         * the creator removed. Keeps the playlist available offline while showing real, current data.
         */
        suspend fun syncSavedPlaylistVideos(
            playlistId: String,
            remoteVideos: List<Video>,
        ) {
            if (remoteVideos.isEmpty()) return
            videoDao.mergeMetadata(remoteVideos.map(::normalizedEntity))
            playlistDao.replacePlaylistVideos(playlistId, remoteVideos.map { it.id }.distinct())
        }

        suspend fun getPlaylistEntity(playlistId: String): PlaylistEntity? = playlistDao.getPlaylist(playlistId)

        fun observePlaylistEntity(playlistId: String): Flow<PlaylistEntity?> = playlistDao.observePlaylist(playlistId)

        suspend fun getPlaylistInfo(playlistId: String): PlaylistInfo? {
            val entity = playlistDao.getPlaylist(playlistId) ?: return null
            return PlaylistInfo(
                id = entity.id,
                name = entity.name,
                description = entity.description,
                videoCount = 0,
                thumbnailUrl = entity.thumbnailUrl,
                isPrivate = entity.isPrivate,
                createdAt = entity.createdAt,
            )
        }
    }

private fun PlaylistWithCount.toPlaylistInfo() =
    PlaylistInfo(
        id = playlist.id,
        name = playlist.name,
        description = playlist.description,
        videoCount = videoCount,
        thumbnailUrl = playlist.thumbnailUrl,
        isPrivate = playlist.isPrivate,
        createdAt = playlist.createdAt,
        position = playlist.position,
    )
