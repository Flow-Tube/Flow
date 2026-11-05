package io.github.aedev.flow.data.music

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.download.CachedSongMigration
import io.github.aedev.flow.data.download.LegacySongDownloads
import io.github.aedev.flow.data.local.dao.DownloadDao
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.local.entity.DownloadWithItems
import io.github.aedev.flow.data.local.safePreferencesDataStore
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.model.withTypedArtists
import io.github.aedev.flow.data.profile.StoreScope
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.data.video.downloader.request.StoredArtist
import io.github.aedev.flow.data.video.downloader.request.toDownloadRequest
import io.github.aedev.flow.data.video.downloader.tags.DownloadKind
import io.github.aedev.flow.data.video.downloader.work.DownloadController
import io.github.aedev.flow.data.video.storage.DownloadFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.downloadDataStore: DataStore<Preferences> by safePreferencesDataStore(name = "downloads", scope = StoreScope.DEVICE)

data class DownloadedTrack(
    val track: MusicTrack,
    val filePath: String,
    val downloadedAt: Long = System.currentTimeMillis(),
    val fileSize: Long = 0,
    val downloadId: Long = -1,
    /** What the row shows for the file: its bitrate, and the track for a video's dub. */
    val quality: String? = null,
)

/**
 * The music library's view of downloads: every finished audio download, with the album and
 * artists its row keeps, plus the few songs older versions cached through Media3 instead of saving
 * them as files, until each is downloaded again as one. New songs go through the shared queue.
 */
@Singleton
class DownloadManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val legacySongs: LegacySongDownloads,
        private val videoDownloadManager: VideoDownloadManager,
        private val downloadDao: DownloadDao,
        private val controller: DownloadController,
    ) {
        private val gson = Gson()
        private val migration = Mutex()

        @Volatile
        private var migrated = false

        fun isCachedForOffline(mediaId: String): Boolean = legacySongs.isCachedForOffline(mediaId)

        val downloadedTracks: Flow<List<DownloadedTrack>> =
            combine(
                videoDownloadManager.audioOnlyDownloads,
                context.downloadDataStore.data.map { parseStoredTracks(it[STORED_TRACKS_KEY]) },
            ) { rows, stored ->
                val files =
                    rows
                        .filter { it.overallStatus == DownloadItemStatus.COMPLETED }
                        .mapNotNull(::toDownloadedTrack)
                val onDisk = files.mapTo(HashSet()) { it.track.videoId }
                val cached = legacySongs.completedIds()
                val cachedOnly = stored.filter { it.track.videoId !in onDisk && it.track.videoId in cached }
                files + cachedOnly
            }.onStart { migrateStoredMetadata() }
                .flowOn(Dispatchers.IO)

        /** Queues [track]; a song already downloaded or waiting is left as it is. */
        suspend fun downloadTrack(track: MusicTrack): Result<String> =
            runCatching {
                controller.enqueue(track.toDownloadRequest())
                track.videoId
            }.onFailure { Log.e(TAG, "Could not queue ${track.videoId}", it) }

        suspend fun isDownloaded(videoId: String): Boolean =
            getDownloadedTrackPath(videoId) != null || videoId in legacySongs.completedIds()

        suspend fun getDownloadedTrackPath(videoId: String): String? =
            withContext(Dispatchers.IO) {
                val download = videoDownloadManager.getDownloadWithItems(videoId) ?: return@withContext null
                if (!download.isAudioOnly || download.overallStatus != DownloadItemStatus.COMPLETED) return@withContext null
                download.items
                    .firstOrNull { it.status == DownloadItemStatus.COMPLETED && DownloadFiles.exists(context, it.filePath) }
                    ?.filePath
            }

        suspend fun deleteDownload(videoId: String) {
            videoDownloadManager
                .getDownloadWithItems(videoId)
                ?.takeIf { it.isAudioOnly }
                ?.let { videoDownloadManager.deleteDownload(videoId) }
            if (videoId in legacySongs.completedIds()) legacySongs.remove(videoId)
            context.downloadDataStore.edit { prefs ->
                val remaining = parseStoredTracks(prefs[STORED_TRACKS_KEY]).filterNot { it.track.videoId == videoId }
                prefs[STORED_TRACKS_KEY] = gson.toJson(remaining)
            }
        }

        private fun toDownloadedTrack(row: DownloadWithItems): DownloadedTrack? {
            val item =
                row.items.firstOrNull {
                    it.status == DownloadItemStatus.COMPLETED && DownloadFiles.exists(context, it.filePath)
                } ?: return null
            val entity = row.download
            val artists = StoredArtist.decode(entity.artistsJson).map { MusicArtist(it.name, it.id) }
            val cover = entity.thumbnailPath?.let { "file://$it" }
            return DownloadedTrack(
                track =
                    MusicTrack(
                        videoId = entity.videoId,
                        title = entity.title,
                        artist = artists.joinToString(", ") { it.name }.ifBlank { entity.uploader },
                        thumbnailUrl = cover ?: entity.thumbnailUrl,
                        duration = entity.duration.toInt(),
                        views = entity.viewCount,
                        album = entity.album.orEmpty(),
                        channelId = entity.channelId,
                        albumId = entity.albumId,
                        artists = artists,
                    ),
                filePath = item.filePath,
                quality = item.quality,
                downloadedAt = entity.createdAt,
                fileSize = item.totalBytes.takeIf { it > 0 } ?: item.downloadedBytes,
            )
        }

        /**
         * Older versions kept a song's album and artists only in a DataStore list beside its row.
         * Once, those are written into the rows, so the music list reads Room alone from then on.
         */
        private suspend fun migrateStoredMetadata() {
            if (migrated) return
            migration.withLock {
                if (migrated) return
                val prefs = context.downloadDataStore.data.first()
                if (prefs[MIGRATED_KEY] != true) {
                    parseStoredTracks(prefs[STORED_TRACKS_KEY]).forEach { stored ->
                        val track = stored.track
                        if (downloadDao.getDownloadByVideoId(track.videoId) == null) return@forEach
                        downloadDao.updateMusicMetadata(
                            videoId = track.videoId,
                            kind = DownloadKind.MUSIC,
                            album = track.album.takeIf { it.isNotBlank() },
                            albumId = track.albumId,
                            artistsJson =
                                track.artists.takeIf { it.isNotEmpty() }?.let { artists ->
                                    StoredArtist.encode(artists.map { StoredArtist(it.name, it.id) })
                                },
                            channelId = track.channelId,
                            uploader = track.artist,
                        )
                    }
                    context.downloadDataStore.edit { it[MIGRATED_KEY] = true }
                }
                migrateCachedSongs(parseStoredTracks(prefs[STORED_TRACKS_KEY]))
                migrated = true
            }
        }

        /**
         * Queues each song an older version only cached as a file download of its own, once: a song
         * that already has a row, finished or failed, is left to it. A cached copy is dropped only
         * after its file exists, so the song stays playable offline throughout.
         */
        private suspend fun migrateCachedSongs(stored: List<DownloadedTrack>) {
            legacySongs.retireService()
            val cached = legacySongs.completedIds()
            if (cached.isEmpty()) return
            val songs = stored.associateBy { it.track.videoId }.filterKeys { it in cached }
            val withFile = songs.keys.filterTo(HashSet()) { getDownloadedTrackPath(it) != null }
            val withRow = songs.keys.filterTo(HashSet()) { downloadDao.exists(it) }
            val plan = CachedSongMigration.of(songs.keys.toList(), cached, { it in withFile }, { it in withRow })
            plan.drop.forEach { legacySongs.remove(it) }
            plan.queue.forEach { controller.enqueue(songs.getValue(it).track.toDownloadRequest()) }
        }

        private fun parseStoredTracks(json: String?): List<DownloadedTrack> =
            runCatching {
                val type = object : TypeToken<List<DownloadedTrack>>() {}.type
                gson
                    .fromJson<List<DownloadedTrack>>(json ?: "[]", type)
                    .orEmpty()
                    .map { it.copy(track = it.track.withTypedArtists()) }
            }.getOrElse {
                Log.w(TAG, "Failed to parse stored music downloads", it)
                emptyList()
            }

        private companion object {
            const val TAG = "MusicDownloads"
            val STORED_TRACKS_KEY = stringPreferencesKey("downloaded_tracks")
            val MIGRATED_KEY = booleanPreferencesKey("downloaded_tracks_in_room")
        }
    }
