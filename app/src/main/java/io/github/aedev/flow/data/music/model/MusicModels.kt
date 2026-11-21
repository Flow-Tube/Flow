package io.github.aedev.flow.data.music.model

import io.github.aedev.flow.data.images.MusicArtwork
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import kotlinx.serialization.Serializable

enum class MusicItemType { SONG, ALBUM, PLAYLIST, ARTIST }

/**
 * Play-source prefix marking a genre/mood-scoped surface (genre rows, mood
 * chips). The player strips it for the "Playing from" label and hands the genre
 * to the music brain as listen-context provenance.
 */
const val MUSIC_GENRE_SOURCE_PREFIX = "genre:"

/** A song listed as a video (a YouTube Music playlist, a queued download) as the music player's track. */
fun Video.toMusicTrack(): MusicTrack =
    MusicTrack(
        videoId = id,
        title = title,
        artist = channelName,
        thumbnailUrl = thumbnailUrl,
        duration = duration,
        views = viewCount.coerceAtLeast(0),
        channelId = channelId,
    )

/** A track opened in the video player, carrying what the list already showed for it. */
fun MusicTrack.toVideo(): Video =
    Video(
        id = videoId,
        title = title,
        channelName = artist,
        channelId = channelId,
        thumbnailUrl = thumbnailUrl,
        duration = duration,
        viewCount = views,
        uploadDate = "",
    )

@Serializable
data class MusicTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val duration: Int,
    val views: Long = 0,
    val likes: Long = 0,
    val sourceUrl: String = "", // Full URL for NewPipe extraction
    val album: String = "",
    val channelId: String = "",
    val isExplicit: Boolean? = false,
    val isVideoSong: Boolean = false,
    val albumId: String? = null,
    val artists: List<MusicArtist> = emptyList(),
    val itemType: MusicItemType = MusicItemType.SONG,
) {
    val highResThumbnailUrl: String
        get() = ThumbnailUrlResolver.resolveMusicThumbnail(videoId, thumbnailUrl, 1080)

    // Gson allocates tracks without running initializers, so the cache starts null rather than lazy.
    @kotlin.jvm.Transient
    @kotlinx.serialization.Transient
    private var listThumbnailCache: String? = null

    val listThumbnailUrl: String
        get() =
            listThumbnailCache
                ?: ThumbnailUrlResolver.resolveMusicThumbnail(videoId, thumbnailUrl, MusicArtwork.LIST_SIZE).also {
                    listThumbnailCache = it
                }
}

@Serializable
data class MusicArtist(
    val name: String,
    val id: String? = null,
)

/**
 * Repairs a Gson-deserialized track whose [MusicTrack.artists] may hold untyped maps
 * instead of [MusicArtist] objects: release builds that lose the field's generic
 * signature make Gson fall back to LinkedTreeMap entries, which crash with a
 * ClassCastException on first element access (issue #996). filterIsInstance performs
 * only instanceof checks, so it is safe on a poisoned list; bad entries are dropped
 * and the plain [MusicTrack.artist]/[MusicTrack.channelId] fallbacks take over.
 * Call it on every Gson read path that yields a [MusicTrack].
 */
fun MusicTrack.withTypedArtists(): MusicTrack {
    val raw: List<*>? = artists
    if (raw == null) return copy(artists = emptyList())
    return if (raw.all { it is MusicArtist }) this else copy(artists = raw.filterIsInstance<MusicArtist>())
}

data class DailyDiscoverItem(
    val seed: MusicTrack,
    val recommendation: MusicTrack,
)

data class CommunityMusicPlaylist(
    val playlist: MusicPlaylist,
    val tracks: List<MusicTrack>,
)

data class MusicPlaylist(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val trackCount: Int = 0,
    val author: String = "",
    // Structured attribution for "not interested"/"don't recommend" filtering.
    // `author` is a display subtitle — album cards put the release YEAR there —
    // so feedback matching must never rely on parsing it.
    val authorId: String? = null,
    val authorName: String? = null,
)

data class PlaylistDetails(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val author: String,
    val authorId: String? = null,
    val authorAvatarUrl: String? = null,
    val trackCount: Int,
    val description: String? = null,
    val views: Long? = null,
    val durationText: String? = null,
    val dateText: String? = null,
    val tracks: List<MusicTrack> = emptyList(),
    val continuation: String? = null,
    val otherVersions: List<MusicPlaylist> = emptyList(),
    /** The song count YouTube states for the whole playlist, when it states one. */
    val totalTrackCount: Int? = null,
)

data class ArtistDetails(
    val name: String,
    val channelId: String,
    val thumbnailUrl: String,
    val subscriberCount: Long,
    val monthlyListenersText: String? = null,
    val description: String = "",
    val descriptionLinks: List<MusicTextLink> = emptyList(),
    val bannerUrl: String = "",
    val topTracks: List<MusicTrack> = emptyList(),
    val albums: List<MusicPlaylist> = emptyList(),
    val singles: List<MusicPlaylist> = emptyList(),
    val videos: List<MusicTrack> = emptyList(),
    val relatedArtists: List<ArtistDetails> = emptyList(),
    val featuredOn: List<MusicPlaylist> = emptyList(),
    val isSubscribed: Boolean = false,
    val albumsBrowseId: String? = null,
    val albumsParams: String? = null,
    val singlesBrowseId: String? = null,
    val singlesParams: String? = null,
    val topTracksBrowseId: String? = null,
    val topTracksParams: String? = null,
    /** The header's shuffle of the artist's whole catalogue, played through `next`. */
    val shufflePlaylistId: String? = null,
    val shuffleParams: String? = null,
    /** The header's artist mix. */
    val radioPlaylistId: String? = null,
    val radioParams: String? = null,
)

/** A link over the characters of a text from [start] up to [end]. */
data class MusicTextLink(
    val start: Int,
    val end: Int,
    val url: String,
)

data class MusicCharts(
    val countryCode: String?,
    val songs: List<MusicTrack> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(),
    val artists: List<ArtistDetails> = emptyList(),
)

data class RelatedMusic(
    val seed: MusicTrack?,
    val seedArtistId: String?,
    val tracks: List<MusicTrack>,
    val radioTracks: List<MusicTrack>,
    val otherPerformances: List<MusicTrack>,
    val similarArtists: List<ArtistDetails>,
    val playlists: List<MusicPlaylist>,
    val artistAlbums: List<MusicPlaylist>,
)
