package io.github.aedev.flow.data.playlist

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.toVideo
import io.github.aedev.flow.utils.sharedFileUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val SHARE_DIR = "playlists"

/** What an import did. */
sealed interface PlaylistImport {
    data class Imported(
        val playlistId: String,
        val name: String,
        val videoCount: Int,
        val isMusic: Boolean = false,
    ) : PlaylistImport

    /** Several playlists from a zip or several files; [playlistCount] of them went in. */
    data class ImportedSeveral(
        val playlistCount: Int,
    ) : PlaylistImport

    data object NotAPlaylist : PlaylistImport

    data object TooNew : PlaylistImport

    data object Empty : PlaylistImport

    data object Unreadable : PlaylistImport
}

/**
 * Writes playlists to files, for saving or sharing, and reads them back: as new playlists, or into
 * Watch later, the likes and Saved Shorts for files that hold those.
 */
@Singleton
class PlaylistTransfer
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val playlists: PlaylistRepository,
        private val likes: LikedVideosRepository,
    ) {
        /** Saves the playlist into [target], a document the viewer picked; false when it could not be written. */
        suspend fun writeTo(
            target: Uri,
            name: String,
            description: String,
            videos: List<Video>,
            isMusic: Boolean = false,
        ): Boolean =
            withContext(Dispatchers.IO) {
                runCatching {
                    val text = PlaylistFileCodec.encode(name, description, videos, System.currentTimeMillis(), isMusic)
                    checkNotNull(context.contentResolver.openOutputStream(target, "wt")).use { it.write(text.toByteArray()) }
                }.isSuccess
            }

        /** A copy in the cache another app can read, replacing the last one shared; null when it could not be written. */
        suspend fun shareableCopy(
            name: String,
            description: String,
            videos: List<Video>,
            isMusic: Boolean = false,
        ): Uri? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
                    dir.listFiles()?.forEach(File::delete)
                    val file = File(dir, PlaylistFileCodec.fileName(name))
                    file.writeText(PlaylistFileCodec.encode(name, description, videos, System.currentTimeMillis(), isMusic))
                    sharedFileUri(context, file)
                }.getOrNull()
            }

        /**
         * Saves every playlist of the viewer's own, Watch later, liked videos, liked music and Saved
         * Shorts into [target] as one zip. Null when it could not be written, else how many playlists it holds.
         */
        suspend fun writeLibraryTo(target: Uri): Int? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val files = libraryFiles(System.currentTimeMillis())
                    checkNotNull(context.contentResolver.openOutputStream(target, "wt")).use { PlaylistArchive.write(it, files) }
                    files.size
                }.getOrNull()
            }

        private suspend fun libraryFiles(exportedAt: Long): List<PlaylistArchiveFile> {
            val own =
                playlists.getUserCreatedVideoPlaylistsFlow().first().map { it to false } +
                    playlists.getUserCreatedMusicPlaylistsFlow().first().map { it to true }
            val ownFiles =
                own.map { (info, isMusic) ->
                    val videos = playlists.getPlaylistVideosWithAddedAtFlow(info.id).first()
                    PlaylistArchiveFile(info.name, PlaylistFileCodec.encode(info.name, info.description, videos, exportedAt, isMusic))
                }
            val lists =
                PlaylistFileKind.entries.mapNotNull { kind ->
                    val videos = listVideos(kind).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val name = context.getString(kind.titleRes)
                    val isMusic = kind == PlaylistFileKind.LIKED_MUSIC
                    PlaylistArchiveFile(name, PlaylistFileCodec.encode(name, "", videos, exportedAt, isMusic, kind))
                }
            return lists + ownFiles
        }

        private suspend fun listVideos(kind: PlaylistFileKind): List<Video> =
            when (kind) {
                PlaylistFileKind.WATCH_LATER, PlaylistFileKind.SAVED_SHORTS -> {
                    playlists.getPlaylistVideosWithAddedAtFlow(kind.listId).first()
                }

                PlaylistFileKind.LIKED -> {
                    likes.getLikedVideosFlow().first().map(::likedVideo)
                }

                PlaylistFileKind.LIKED_MUSIC -> {
                    likes.getLikedMusicFlow().first().map(::likedVideo)
                }
            }

        private fun likedVideo(like: LikedVideoInfo): Video =
            like.toVideo().copy(timestamp = 0L, viewCount = 0L, addedAtInPlaylist = like.likedAt)

        /** Adds the playlist in [source] as a new playlist of the viewer's own, or into the list it holds. */
        suspend fun import(
            source: Uri,
            fallbackName: String,
        ): PlaylistImport = importAll(listOf(source), fallbackName)

        /**
         * Imports every playlist file in [sources], each a single file or a zip of them. An empty
         * playlist in a zip is kept, so a whole library comes back as it was saved.
         */
        suspend fun importAll(
            sources: List<Uri>,
            fallbackName: String,
        ): PlaylistImport =
            withContext(Dispatchers.IO) {
                val results = ArrayList<PlaylistImport>()
                // Each new playlist takes the next older time, so the list sorted by newest keeps the saved order.
                var createdAt = System.currentTimeMillis()
                sources.forEach { source ->
                    val read =
                        runCatching {
                            context.contentResolver.openInputStream(source)?.use { stream ->
                                PlaylistArchive.read(stream) { text, inArchive ->
                                    results += importText(text, fallbackName, createdAt--, keepEmpty = inArchive)
                                }
                            }
                        }.getOrNull() == true
                    if (!read) results += PlaylistImport.Unreadable
                }
                val imported = results.count { it is PlaylistImport.Imported }
                when {
                    results.size == 1 -> results.single()
                    imported == 0 -> results.firstOrNull() ?: PlaylistImport.Unreadable
                    else -> PlaylistImport.ImportedSeveral(imported)
                }
            }

        private suspend fun importText(
            text: String,
            fallbackName: String,
            createdAt: Long,
            keepEmpty: Boolean,
        ): PlaylistImport =
            when (val read = PlaylistFileCodec.decode(text)) {
                PlaylistFileRead.NotAPlaylist -> {
                    PlaylistImport.NotAPlaylist
                }

                PlaylistFileRead.TooNew -> {
                    PlaylistImport.TooNew
                }

                is PlaylistFileRead.Read -> {
                    val videos = read.file.videos.map(PlaylistFileVideo::toVideo)
                    val kind = PlaylistFileKind.of(read.file)
                    when {
                        videos.isEmpty() && (kind != null || !keepEmpty) -> PlaylistImport.Empty
                        kind != null -> importInto(kind, videos)
                        else -> importAsPlaylist(read.file, videos, fallbackName, createdAt)
                    }
                }
            }

        private suspend fun importAsPlaylist(
            file: PlaylistFile,
            videos: List<Video>,
            fallbackName: String,
            createdAt: Long,
        ): PlaylistImport.Imported {
            val name =
                file.playlist.name
                    .trim()
                    .ifEmpty { fallbackName }
            val isMusic = file.playlist.isMusic
            val id = playlists.importPlaylist(name, file.playlist.description, videos, isMusic, createdAt)
            return PlaylistImport.Imported(id, name, videos.size, isMusic)
        }

        /** Adds what [kind]'s list doesn't hold yet; the count is of videos added. */
        private suspend fun importInto(
            kind: PlaylistFileKind,
            videos: List<Video>,
        ): PlaylistImport.Imported {
            val added =
                when (kind) {
                    PlaylistFileKind.WATCH_LATER, PlaylistFileKind.SAVED_SHORTS -> {
                        playlists.importIntoFixedList(kind.listId, videos)
                    }

                    PlaylistFileKind.LIKED, PlaylistFileKind.LIKED_MUSIC -> {
                        likes.importLikes(likesOf(videos, isMusic = kind == PlaylistFileKind.LIKED_MUSIC)).size
                    }
                }
            return PlaylistImport.Imported(kind.listId, context.getString(kind.titleRes), added, kind == PlaylistFileKind.LIKED_MUSIC)
        }
    }

/** Likes in the file's order: a file without dates places each just after the one before it. */
internal fun likesOf(
    videos: List<Video>,
    isMusic: Boolean,
    now: Long = System.currentTimeMillis(),
): List<LikedVideoInfo> =
    videos.mapIndexed { index, video ->
        LikedVideoInfo(
            videoId = video.id,
            title = video.title,
            thumbnail = video.thumbnailUrl,
            channelName = video.channelName,
            likedAt = video.addedAtInPlaylist ?: (now - index),
            isMusic = isMusic,
            channelId = video.channelId.ifBlank { null },
            durationSeconds = video.duration,
        )
    }

private val PlaylistFileKind.listId: String
    get() =
        when (this) {
            PlaylistFileKind.WATCH_LATER -> PlaylistRepository.WATCH_LATER_ID
            PlaylistFileKind.LIKED -> PlaylistRepository.LIKED_VIDEOS_ID
            PlaylistFileKind.LIKED_MUSIC -> PlaylistRepository.LIKED_MUSIC_ID
            PlaylistFileKind.SAVED_SHORTS -> PlaylistRepository.SAVED_SHORTS_ID
        }

@get:StringRes
private val PlaylistFileKind.titleRes: Int
    get() =
        when (this) {
            PlaylistFileKind.WATCH_LATER -> R.string.watch_later
            PlaylistFileKind.LIKED -> R.string.liked_videos_playlist
            PlaylistFileKind.LIKED_MUSIC -> R.string.liked_music_playlist
            PlaylistFileKind.SAVED_SHORTS -> R.string.library_saved_shorts_label
        }
