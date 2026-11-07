package io.github.aedev.flow.data.music.artwork

import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.model.musicArtistKey
import io.github.aedev.flow.data.music.model.musicBaseTitle
import io.github.aedev.flow.data.music.model.musicTitleKey
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import kotlinx.serialization.Serializable

/** What the artwork service answers: the matched song and, when its album has one, the loop. */
@Serializable
internal data class ArtworkAnswer(
    val name: String? = null,
    val artist: String? = null,
    val animated: String? = null,
    val animatedVertical: String? = null,
    val albumId: String? = null,
) {
    /** The loops in this answer, or null when its album has none. */
    fun loop(): AnimatedLoop? = AnimatedLoop(animated, animatedVertical).takeIf { it.square != null || it.tall != null }
}

/** What Apple's public catalogue search answers: the songs found and the release each is on. */
@Serializable
internal data class CatalogSearch(
    val results: List<CatalogSong> = emptyList(),
)

@Serializable
internal data class CatalogSong(
    val trackName: String? = null,
    val artistName: String? = null,
    val collectionId: Long? = null,
    val collectionName: String? = null,
    val collectionArtistName: String? = null,
    val trackCount: Int = 0,
)

/** An album's loops: the square one, and the 3:4 one cut for a tall screen. Either can be missing. */
internal data class AnimatedLoop(
    val square: String?,
    val tall: String?,
) {
    /** The loop for the space it plays in. A tall space takes the square loop when it is all there is. */
    fun urlFor(tallSpace: Boolean): String? = if (tallSpace) tall ?: square else square
}

/** A loop and the song it was looked up for, so it can never show on another song. */
internal class LoadedLoop(
    private val videoId: String,
    private val loop: AnimatedLoop,
) {
    fun urlFor(
        currentVideoId: String?,
        tallSpace: Boolean,
    ): String? = loop.urlFor(tallSpace).takeIf { videoId == currentVideoId }
}

/**
 * How a song is asked for and how an answer is trusted. A loop belongs to an album, so the songs
 * of one album share a single lookup, and an answer counts only when it names the same song by the
 * same artist: a near miss would put another record's artwork on this one.
 */
internal object AnimatedArtworkLookup {
    data class Query(
        val title: String,
        val artist: String,
    )

    /** [track] without the "Unknown Album" that search rows with no album carry, so it isn't asked about as an album. */
    fun withKnownAlbum(track: MusicTrack): MusicTrack =
        if (track.album.equals(UNKNOWN_ALBUM, ignoreCase = true)) track.copy(album = "") else track

    /** Recommendations and search rows name every artist in one string, so its first name stands for the row. */
    fun artist(track: MusicTrack): String = credited(track).first()

    private fun credited(track: MusicTrack): List<String> =
        track.artists
            .map { it.name }
            .ifEmpty {
                track.artist
                    .split(ARTIST_SEPARATOR)
                    .map(String::trim)
                    .filter(String::isNotEmpty)
            }.ifEmpty { listOf(track.artist) }

    /** The song as titled, then without brackets or featured artists, each asked for once. */
    fun queries(track: MusicTrack): List<Query> {
        val artist = artist(track).removeSuffix(TOPIC_SUFFIX).trim()
        return listOf(track.title, musicBaseTitle(track.title))
            .filter(String::isNotBlank)
            .distinct()
            .map { Query(it, artist) }
    }

    fun key(track: MusicTrack): String {
        val release = track.album.takeIf(String::isNotBlank) ?: track.title
        return "${musicArtistKey(artist(track))}|${musicTitleKey(release)}"
    }

    fun accepts(
        track: MusicTrack,
        answer: ArtworkAnswer,
    ): Boolean {
        val name = answer.name ?: return false
        val artist = answer.artist ?: return false
        val artists = credited(track)
        return musicTitleKey(name, artists) == musicTitleKey(track.title, artists) &&
            musicArtistKey(artist).contains(musicArtistKey(artist(track)))
    }

    fun songSearch(track: MusicTrack): String = "${musicBaseTitle(track.title)} ${artist(track).removeSuffix(TOPIC_SUFFIX).trim()}"

    /**
     * [track] as the first real song row in [rows] that is the same song by the same artist. Video
     * rows are left out: only a song row's album is the one the song was released on.
     */
    fun albumVersion(
        track: MusicTrack,
        rows: List<SongItem>,
    ): MusicTrack? =
        rows
            .firstOrNull { row ->
                row.musicVideoType == MUSIC_VIDEO_TYPE_ATV &&
                    row.album?.name?.isNotBlank() == true &&
                    accepts(track, ArtworkAnswer(row.title, row.artists.joinToString { it.name }))
            }?.let { row ->
                track.copy(title = row.title, album = row.album?.name.orEmpty(), duration = row.duration ?: 0)
            }

    /**
     * The album ids worth asking about after [track]'s own album had no loop, best first. Only the
     * same song by the same artist counts, on a release named like the song or its album: a
     * compilation that happens to carry the song has its own artwork, which is worse than none.
     */
    fun otherReleases(
        track: MusicTrack,
        songs: List<CatalogSong>,
        asked: Set<String>,
    ): List<String> {
        val related = nameWords(track.album) + nameWords(track.title)
        val songIsAVersion = VERSION_RELEASE.containsMatchIn(track.title)
        return songs
            .asSequence()
            .filter { accepts(track, ArtworkAnswer(it.trackName, it.artistName)) }
            .filter { it.collectionId != null && it.collectionId.toString() !in asked }
            .filter { it.collectionArtistName?.equals(VARIOUS_ARTISTS, ignoreCase = true) != true }
            .filter { nameWords(it.collectionName.orEmpty()).any(related::contains) }
            .filter { songIsAVersion || !VERSION_RELEASE.containsMatchIn(it.collectionName.orEmpty()) }
            .distinctBy { it.collectionId }
            .sortedWith(compareBy({ it.collectionName.orEmpty().endsWith(SINGLE_SUFFIX) }, { -it.trackCount }))
            .map { it.collectionId.toString() }
            .take(MAX_OTHER_RELEASES)
            .toList()
    }

    private fun nameWords(name: String): Set<String> =
        musicTitleKey(name)
            .split(" ")
            .filter { it.length >= MIN_WORD_LENGTH && it !in COMMON_RELEASE_WORDS }
            .toSet()

    private const val TOPIC_SUFFIX = " - Topic"
    private const val UNKNOWN_ALBUM = "Unknown Album"
    private val ARTIST_SEPARATOR = Regex("""\s*[,&]\s*""")
    private const val VARIOUS_ARTISTS = "Various Artists"
    private const val SINGLE_SUFFIX = "- Single"
    private const val MAX_OTHER_RELEASES = 2
    private const val MIN_WORD_LENGTH = 2
    private val VERSION_RELEASE =
        Regex("""\b(remix|mix|lofi|lo-fi|flip|synthwave|instrumental|karaoke|slowed|reverb|sped up|nightcore)\b""", RegexOption.IGNORE_CASE)
    private val COMMON_RELEASE_WORDS =
        setOf(
            "the",
            "an",
            "of",
            "from",
            "ep",
            "single",
            "album",
            "deluxe",
            "edition",
            "version",
            "original",
            "motion",
            "picture",
            "soundtrack",
            "ost",
            "hindi",
            "tamil",
            "telugu",
            "songs",
            "hits",
            "best",
            "greatest",
            "music",
        )
}
