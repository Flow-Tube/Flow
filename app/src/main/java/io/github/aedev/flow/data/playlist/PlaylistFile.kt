package io.github.aedev.flow.data.playlist

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val FORMAT = "flow-playlist"
private const val VERSION = 1
private const val MAX_FILE_NAME_LENGTH = 80
private val VideoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")
private val UnsafeFileNameChars = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

/**
 * A playlist as a file someone else can import: its name, description and every video with the
 * metadata a list row needs, in the playlist's own order. Device files are left out; their ids name
 * nothing on another phone.
 */
@Serializable
data class PlaylistFile(
    val format: String,
    val version: Int,
    val exportedAt: Long,
    val playlist: PlaylistFileHeader,
    val videos: List<PlaylistFileVideo>,
)

@Serializable
data class PlaylistFileHeader(
    val name: String,
    val description: String = "",
    /** A music playlist imports into the Music library; absent in files older than music export. */
    val isMusic: Boolean = false,
    /** Set when the file holds one of Flow's own lists, which an import adds to instead of making a new playlist. */
    val kind: String? = null,
)

/** Flow's own lists, each of which a file can say it holds. */
enum class PlaylistFileKind(
    val wire: String,
) {
    WATCH_LATER("watch_later"),
    LIKED("liked"),
    LIKED_MUSIC("liked_music"),
    SAVED_SHORTS("saved_shorts"),
    ;

    companion object {
        /** Null for an ordinary playlist, and for a list only a newer Flow knows, which then imports as a playlist. */
        fun of(file: PlaylistFile): PlaylistFileKind? = entries.firstOrNull { it.wire == file.playlist.kind }
    }
}

@Serializable
data class PlaylistFileVideo(
    val id: String,
    val title: String = "",
    val channelName: String = "",
    val channelId: String = "",
    val channelThumbnailUrl: String = "",
    val thumbnailUrl: String = "",
    val durationSeconds: Int = 0,
    val viewCount: Long = 0L,
    val uploadDate: String = "",
    val publishedAt: Long = 0L,
    val description: String = "",
    val isMusic: Boolean = false,
    val addedAt: Long? = null,
) {
    fun toVideo(): Video =
        Video(
            id = id,
            title = title,
            channelName = channelName,
            channelId = channelId,
            thumbnailUrl = thumbnailUrl.ifBlank { ThumbnailUrlResolver.buildHighQualityYoutubeThumbnail(id) },
            duration = durationSeconds.coerceAtLeast(0),
            viewCount = viewCount.coerceAtLeast(0L),
            uploadDate = uploadDate,
            timestamp = publishedAt,
            description = description,
            channelThumbnailUrl = channelThumbnailUrl,
            isMusic = isMusic,
            addedAtInPlaylist = addedAt,
        )
}

/** What reading a file found. */
sealed interface PlaylistFileRead {
    data class Read(
        val file: PlaylistFile,
    ) : PlaylistFileRead

    /** Not JSON, or JSON of some other kind. */
    data object NotAPlaylist : PlaylistFileRead

    /** Written by a newer Flow in a format this one cannot read. */
    data object TooNew : PlaylistFileRead
}

object PlaylistFileCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = false
        }

    fun encode(
        name: String,
        description: String,
        videos: List<Video>,
        exportedAt: Long,
        isMusic: Boolean = false,
        kind: PlaylistFileKind? = null,
    ): String =
        json.encodeToString(
            PlaylistFile.serializer(),
            PlaylistFile(
                format = FORMAT,
                version = VERSION,
                exportedAt = exportedAt,
                playlist = PlaylistFileHeader(name = name, description = description, isMusic = isMusic, kind = kind?.wire),
                videos =
                    videos.filter { VideoIdPattern.matches(it.id) }.map { video ->
                        PlaylistFileVideo(
                            id = video.id,
                            title = video.title,
                            channelName = video.channelName,
                            channelId = video.channelId,
                            channelThumbnailUrl = video.channelThumbnailUrl,
                            thumbnailUrl = video.thumbnailUrl,
                            durationSeconds = video.duration,
                            viewCount = video.viewCount,
                            uploadDate = video.uploadDate,
                            publishedAt = video.timestamp,
                            description = video.description,
                            isMusic = video.isMusic,
                            addedAt = video.addedAtInPlaylist,
                        )
                    },
            ),
        )

    /** Reads [text], keeping only well-formed YouTube ids, each once. */
    fun decode(text: String): PlaylistFileRead {
        val file = runCatching { json.decodeFromString(PlaylistFile.serializer(), text) }.getOrNull()
        return when {
            file == null || file.format != FORMAT -> PlaylistFileRead.NotAPlaylist
            file.version > VERSION -> PlaylistFileRead.TooNew
            else -> PlaylistFileRead.Read(file.copy(videos = file.videos.filter { VideoIdPattern.matches(it.id) }.distinctBy { it.id }))
        }
    }

    /** A name for the saved file that every file system accepts. */
    fun fileName(playlistName: String): String {
        val base =
            playlistName
                .replace(UnsafeFileNameChars, "_")
                .trim()
                .take(MAX_FILE_NAME_LENGTH)
                .trim()
        return "${base.ifEmpty { FORMAT }}.json"
    }
}
