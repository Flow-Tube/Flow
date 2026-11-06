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

    fun artist(track: MusicTrack): String = track.artists.firstOrNull()?.name ?: track.artist

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
        val artists = track.artists.map { it.name }.ifEmpty { listOf(track.artist) }
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

    private const val TOPIC_SUFFIX = " - Topic"
}
